package essentials

import arc.ApplicationListener
import arc.Core
import arc.files.Fi
import arc.util.CommandHandler
import arc.util.async.Threads.sleep
import essentials.command.ClientCommander
import essentials.command.ServerCommander
import essentials.features.*
import essentials.internal.CrashReport
import essentials.internal.Log
import essentials.internal.PluginException
import essentials.internal.Tool
import essentials.network.Client
import essentials.network.Server
import essentials.network.WebServer
import essentials.thread.*
import mindustry.Vars.netServer
import mindustry.core.Version
import mindustry.mod.Plugin
import org.hjson.JsonValue
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.sql.SQLException
import java.util.*
import java.util.concurrent.*
import java.util.jar.JarFile
import java.util.zip.ZipFile
import kotlin.system.exitProcess

class Main : Plugin() {
    companion object {
        val timer = Timer()
        val mainThread: ExecutorService = Executors.newCachedThreadPool()
        val pluginRoot: Fi = Core.settings.dataDirectory.child("mods/Essentials/")
    }

    init {
        //checkServerVersion() // Temporary disabled
        fileExtract()

        // 서버 로비기능 설정
        if (!Core.settings.has("isLobby")) {
            Core.settings.put("isLobby", false)
            Core.settings.saveValues()
        } else if (Core.settings.getBool("isLobby")) {
            Log.info("system.lobby")
            Log.info("Lobby server can only be built by admins!") //TODO 언어별 추가
        }

        // 설정 불러오기
        Config.init()
        Log.info("config.language", Config.language.displayLanguage)

        // 플러그인 데이터 불러오기
        PluginData.loadAll()

        // 플레이어 권한 목록 불러오기
        Permissions.reload(true)

        // 스레드 시작
        mainThread.submit(TriggerThread)
        mainThread.submit(Threads)
        mainThread.submit(ColorNickname)
        if (Config.rollback) timer.scheduleAtFixedRate(AutoRollback, Config.saveTime.toSecondOfDay().toLong(), Config.saveTime.toSecondOfDay().toLong())
        mainThread.submit(PermissionWatch)
        mainThread.submit(WarpBorder)

        // DB 연결
        try {
            PlayerCore.connect(Config.dbServer)
            PlayerCore.create()
            PlayerCore.update()
        } catch (e: SQLException) {
            CrashReport(e)
        }

        // Server 시작
        if (Config.serverEnable) mainThread.submit(Server)

        // Client 연결
        if (Config.clientEnable) {
            if (Config.serverEnable) sleep(1000)
            mainThread.submit(Client)
            Client.wakeup()
        }

        // 기록 시작
        // if (Config.logging) ActivityLog()

        // 이벤트 시작
        Event.register()

        //WebServer.main()

        // 서버 종료 이벤트 설정
        Core.app.addListener(object : ApplicationListener {
            override fun dispose() {
                try {
                    Discord.shutdownNow() // Discord 서비스 종료
                    PlayerCore.saveAll() // 플레이어 데이터 저장
                    PluginData.saveAll() // 플러그인 데이터 저장
                    WarpBorder.interrupt() // 서버간 이동 영역표시 종료
                    mainThread.shutdownNow() // 스레드 종료
                    // config.singleService.shutdownNow(); // 로그 스레드 종료
                    timer.cancel() // 일정 시간마다 실행되는 스레드 종료
                    // 투표 종료
                    Vote.interrupt()
                    PlayerCore.dispose() // DB 연결 종료
                    if (Config.serverEnable) {
                        val servers = Server.list.iterator()
                        while (servers.hasNext()) {
                            val ser = servers.next()
                            if (ser != null) {
                                ser.os.close()
                                ser.br.close()
                                ser.socket.close()
                            }
                        }
                        Server.shutdown()
                        Log.info("server-thread-disabled")
                    }

                    // 클라이언트 종료
                    if (Config.clientEnable && Client.activated) {
                        Client.request(Client.Request.Exit, null, null)
                        Log.info("client.shutdown")
                    }

                    if (Server.serverSocket.isClosed || Client.socket.isClosed || WarpBorder.isInterrupted || !PlayerCore.conn.isClosed) {
                        Log.info("thread-disable-waiting")
                    } else {
                        Log.warn("thread-not-dead")
                    }
                } catch (e: Exception) {
                    CrashReport(e)
                    exitProcess(1) // 오류로 인한 강제 종료
                }
            }
        })

        PluginVars.serverIP = Tool.hostIP()
    }

    override fun init() {
        Tool.ipre.IPDatabasePath = pluginRoot.child("data/IP2LOCATION-LITE-DB1.BIN").absolutePath()
        Tool.ipre.UseMemoryMappedFile = true

        // 채팅 포맷 변경
        netServer.admins.addChatFilter { _, _ -> null }

        // 비 로그인 유저 통제
        netServer.admins.addActionFilter { e ->
            if (e.player == null) return@addActionFilter true
            return@addActionFilter PlayerCore[e.player.uuid()].login
        }
    }

    override fun registerServerCommands(handler: CommandHandler) {
        ServerCommander.register(handler)
    }

    override fun registerClientCommands(handler: CommandHandler) {
        ClientCommander.register(handler)
    }

    private fun checkServerVersion(){
        javaClass.getResourceAsStream("/plugin.json").use { reader ->
            BufferedReader(InputStreamReader(reader)).use { br ->
                val version = JsonValue.readJSON(br).asObject()["version"].asString()
                if (Version.build != PluginVars.buildVersion && Version.revision >= PluginVars.buildRevision) {
                    throw PluginException("Essentials " + version + " plugin only works with Build " + PluginVars.buildVersion + "." + PluginVars.buildRevision + " or higher.")
                }
                PluginVars.pluginVersion = version
            }
        }
    }

    private fun fileExtract(){
        try {
            JarFile(File(Core.settings.dataDirectory.child("mods/Essentials.jar").absolutePath())).use { jar ->
                val enumEntries = jar.entries()
                while (enumEntries.hasMoreElements()) {
                    val file = enumEntries.nextElement()
                    val renamed = file.name.replace("config_folder/", "")
                    if (file.name.startsWith("config_folder") && !pluginRoot.child(renamed).exists()) {
                        if (file.isDirectory) {
                            pluginRoot.child(renamed).file().mkdir()
                            continue
                        }
                        jar.getInputStream(file).use { i -> pluginRoot.child(renamed).write(i, false) }
                    }
                }
            }

            if(pluginRoot.child("data/IP2LOCATION-LITE-DB1.BIN.ZIP").exists()) {
                ZipFile(pluginRoot.child("data/IP2LOCATION-LITE-DB1.BIN.ZIP").absolutePath()).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        if (entry.isDirectory) {
                            File(pluginRoot.child("data").absolutePath(), entry.name).mkdirs()
                        } else {
                            zip.getInputStream(entry).use { input ->
                                File(pluginRoot.child("data").absolutePath(), entry.name).outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                }
                pluginRoot.child("data/IP2LOCATION-LITE-DB1.BIN.ZIP").delete()
                pluginRoot.child("data/LICENSE-CC-BY-SA-4.0.TXT").delete()
                pluginRoot.child("data/README_LITE.TXT").delete()
            }
        } catch (e: IOException) {
            throw PluginException(e)
        }
    }
}
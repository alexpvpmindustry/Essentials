package essentials.features

import arc.struct.Seq
import essentials.Config
import essentials.Main
import essentials.PlayerCore
import mindustry.gen.Playerc

object ColorNickname : Runnable {
    var colorOffset = 0
    var targets = Seq<Playerc>()

    override fun run() {
        while (!Thread.currentThread().isInterrupted) {
            for (player in targets) {
                val p = PlayerCore[player.uuid()]
                if (p.connected && p.colornick) {
                    val name = p.name.replace("\\[(.*?)]".toRegex(), "")
                    nickcolor(name, player)
                } else {
                    player.name(p.name)
                    targets.remove(player)
                }
            }
            Thread.sleep(Config.cupdatei.toLong())
        }
    }

    private fun nickcolor(name: String, player: Playerc) {
        val stringBuilder = StringBuilder()
        val colors = arrayOfNulls<String>(11)
        colors[0] = "[#ff0000]"
        colors[1] = "[#ff7f00]"
        colors[2] = "[#ffff00]"
        colors[3] = "[#7fff00]"
        colors[4] = "[#00ff00]"
        colors[5] = "[#00ff7f]"
        colors[6] = "[#00ffff]"
        colors[7] = "[#007fff]"
        colors[8] = "[#0000ff]"
        colors[9] = "[#8000ff]"
        colors[10] = "[#ff00ff]"
        val newName = arrayOfNulls<String>(name.length)
        for (i in name.indices) {
            val c = name[i]
            var colorIndex = (i + colorOffset) % colors.size
            if (colorIndex < 0) {
                colorIndex += colors.size
            }
            val newtext = colors[colorIndex] + c
            newName[i] = newtext
        }
        colorOffset--
        for (s in newName) {
            stringBuilder.append(s)
        }
        player.name(stringBuilder.toString())
    }
}
package com.astarworks.astera.itemart

import org.yaml.snakeyaml.Yaml
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Original pixel surfaces; integer coordinates and seeded grain keep regeneration byte-stable. */
internal object BlockArt {
    fun renderAll(content: Path): Map<String, ByteArray> {
        val file = content.resolve("textures/sources/placed-blocks.yaml")
        if (!Files.exists(file)) return emptyMap()
        val sources = Yaml().load<Map<String, Map<String, String>>>(Files.readString(file))
        return sources.map { (id, source) ->
            val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
            val base = source.getValue("color").removePrefix("#").toInt(16)
            val random = java.util.Random(id.hashCode().toLong())
            for (y in 0..31) for (x in 0..31) {
                val n = random.nextInt(13) - 6
                val c = Color(base)
                image.setRGB(x, y, Color((c.red+n).coerceIn(0,255), (c.green+n).coerceIn(0,255), (c.blue+n).coerceIn(0,255)).rgb)
            }
            val g = image.createGraphics()
            fun rect(x: Int, y: Int, w: Int, h: Int, rgb: Int) { g.color = Color(rgb); g.fillRect(x,y,w,h) }
            fun line(x: Int, y: Int, xx: Int, yy: Int, rgb: Int) { g.color = Color(rgb); g.drawLine(x,y,xx,yy) }
            when (source.getValue("pattern")) {
                "cross" -> {
                    for (x in 0..31 step 8) { rect(x,0,1,32,0x352922); line(x+2,0,x+2,31,0xb17943) }
                    g.stroke = java.awt.BasicStroke(6f); line(-2,1,33,30,0x392c25); line(-2,30,33,1,0x392c25)
                    g.stroke = java.awt.BasicStroke(3f); line(-2,1,33,30,0xd6a369); line(-2,30,33,1,0xd6a369)
                    rect(0,13,32,6,0x374955); rect(0,13,32,1,0xaebbc3)
                }
                "layers" -> {
                    for (y in 4..30 step 6) { rect(0,y,32,2,0x493128); line(0,y-1,31,y-1,0xba8860) }
                    rect(11,9,10,13,0x46352c); rect(13,11,6,9,0xe0b785); rect(15,13,2,5,0x594334)
                }
                "tiles" -> {
                    for (y in 0..31 step 8) { rect(0,y,32,2,0x513a35); for(x in (if(y%16==0) 0 else 8)..31 step 16) rect(x,y,2,8,0x513a35) }
                    rect(7,7,18,18,0xe2bc9b); rect(9,9,14,14,0x95584f); rect(12,12,8,8,0xe2bc9b)
                }
                "planks" -> {
                    for(x in 0..31 step 8) { rect(x,0,2,32,0x27382d); line(x+3,1,x+3,30,0x9ba879); line(x+6,8,x+6,24,0x48533a) }
                    for(y in listOf(6,23)) { rect(0,y,32,4,0x3f5353); rect(0,y,32,1,0xb5c8bc) }
                }
                "armor" -> {
                    for(y in 0..31 step 8) { rect(0,y,32,1,0x392c32); for(x in (if(y%16==0) 0 else 8)..31 step 16) rect(x,y,1,8,0x392c32) }
                    rect(5,5,22,22,0x252f3b); rect(6,6,20,20,0x778b9d); rect(8,8,16,16,0x4a5e73)
                    line(9,23,23,9,0xafbec9)
                }
                "bags" -> {
                    for (y in 0..31 step 8) for (x in -16..31 step 16) {
                        val xx=x+(if(y%16==0) 0 else 8)
                        rect(xx,y,16,8,0x514a32); rect(xx+1,y+1,14,6,0xbcb37f); rect(xx+2,y+2,12,3,0xd3cb9d)
                        for (sx in 3..13 step 3) rect(xx+sx,y+5,1,2,0x665b3c)
                    }
                }
                "ashlar" -> {
                    rect(0,15,32,2,0x283641); rect(14,0,2,16,0x283641); rect(6,16,2,16,0x283641)
                    for (y in listOf(1,18)) line(1,y,30,y,0xa9bac7)
                    rect(0,10,32,4,0xe2d9b6); rect(0,10,32,1,0xf4ecd3)
                    rect(20,20,7,7,0x334752); rect(22,22,3,3,0xb7c8cd)
                }
                "glass" -> {
                    rect(3,3,26,26,0x214f61); rect(6,6,20,20,0x4f9eab)
                    g.stroke=java.awt.BasicStroke(3f); line(7,20,20,7,0xa8dedb); line(16,25,25,16,0x76bfc5)
                    g.stroke=java.awt.BasicStroke(1f)
                    for(y in listOf(0,29)) rect(0,y,32,3,0x243742)
                    for(x in listOf(0,29)) rect(x,0,3,32,0x243742)
                }
                "seams" -> {
                    for(y in listOf(0,16)) { rect(0,y,32,2,0xb47ce0); rect(0,y+2,32,1,0x573577) }
                    rect(15,0,2,17,0x9d6aca); rect(7,17,2,15,0x9d6aca)
                    line(2,5,12,5,0x606078); line(19,21,29,21,0x606078)
                }
                else -> error("unknown block art pattern $source")
            }
            // The same pale L bracket and dark rivet appears on every player-built surface.
            for ((x,y) in listOf(2 to 2,24 to 24)) {
                rect(x,y,6,2,0xd5d9c5); rect(x,y,2,6,0xd5d9c5); rect(x+1,y+1,1,1,0x25313b)
            }
            g.dispose()
            val bytes=ByteArrayOutputStream().also { ImageIO.write(image,"png",it) }.toByteArray()
            "textures/blocks/$id.png" to bytes
        }.toMap()
    }
}

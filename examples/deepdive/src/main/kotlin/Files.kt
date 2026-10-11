// File routes: send file BYTES in, get output file bytes back — the
// in-process twin of `curl -F data=@data.csv http://.../run`.
//
// Expected output:
//   outputs: [g]
//   8.75,3.50
//   3.50,5.00
import dev.maned.Maned

fun main() {
    Maned.load("pipeline.mnd").use { s ->
        val out = s.runFiles("data" to java.io.File("data.csv").readBytes())
        println("outputs: " + out.keys)
        print(out["g"]!!.decodeToString())
    }
}

import dev.maned.Maned

fun main() {
    Maned.load("scoring.mnd").use { s ->
        val result = s.run("""{"x": [[2,0],[0,2]]}""")
        println("y = " + result.outputs["y"] + " | engine " + Maned.version())
    }
}

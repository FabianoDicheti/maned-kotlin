// Routing from inside the JVM: the flow carries @device(vm1) with
// host = "local", so this run spawns the local Bark VM — the worker's
// REAL compute core — exactly as `maned-run` would. Needs the maned
// toolchain installed (bark-vm on PATH).
//
// Expected output:
//   r = [[19,22],[43,50]]
import dev.maned.Maned

fun main() {
    Maned.load("routed.mnd").use { s ->
        val result = s.run("{}")
        println("r = " + result.outputs["r"])
    }
}

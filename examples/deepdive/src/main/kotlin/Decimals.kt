// Exact decimals + coroutines: quantres=2 makes the integer 1999 mean
// 19.99, and decimals(name) hands back exact BigDecimals — never floats.
// runAsync suspends on a dispatcher while the engine computes.
//
// Expected output:
//   total = [55.48]  mean = [18.49]
//   4 concurrent runs agree: true
import dev.maned.Maned
import dev.maned.runAsync
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    Maned.load("decimal.mnd").use { s ->
        val result = s.run("{}")
        println("total = " + result.decimals("total") + "  mean = " + result.decimals("mean"))

        val reference = result.outputs.toString()
        val agreed = (1..4).map {
            async { s.runAsync("{}").outputs.toString() == reference }
        }.all { it.await() }
        println("4 concurrent runs agree: $agreed")
    }
}

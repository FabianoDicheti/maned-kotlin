package dev.maned

/**
 * One `in::` or `out::` entry of a script's contract, from `GET /health`
 * ([Script.inputs] / [Script.outputs]).
 */
data class IoSpec(
    val name: String,
    /**
     * `"json"`, `"csv"`, `"image"`, `"parquet"`, `"text"` — the `in::`/`out::`
     * source or sink keyword. Empty when the engine did not name one.
     */
    val format: String,
    /**
     * Whether an `in::` has a literal default in the script, so a payload may
     * omit it. Null on an `out::`, which cannot have one.
     */
    val default: Boolean?,
)

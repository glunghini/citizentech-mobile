// Implementação mínima de org.json só para testar a lógica fora do Android (NÃO vai para o app).
package org.json

class JSONException(msg: String) : RuntimeException(msg)

private class P(val s: String) {
  var i = 0
  fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
  fun value(): Any? {
    ws(); if (i >= s.length) throw JSONException("eof")
    return when (val c = s[i]) {
      '{' -> obj(); '[' -> arr(); '"' -> str()
      't' -> lit("true", true); 'f' -> lit("false", false); 'n' -> lit("null", JSONObject.NULL)
      else -> if (c == '-' || c.isDigit()) num() else throw JSONException("bad char $c at $i")
    }
  }
  fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) throw JSONException("bad literal"); i += w.length; return v }
  fun num(): Any { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; val t = s.substring(st, i)
    return t.toIntOrNull() ?: t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw JSONException("bad num") }
  fun str(): String { i++; val sb = StringBuilder()
    while (true) { if (i >= s.length) throw JSONException("unterminated"); val c = s[i++]
      when (c) { '"' -> return sb.toString()
        '\\' -> { val e = s[i++]; when (e) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
          'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 } else -> sb.append(e) } }
        else -> sb.append(c) } } }
  fun obj(): JSONObject { i++; val o = JSONObject(); ws(); if (s[i] == '}') { i++; return o }
    while (true) { ws(); if (s[i] != '"') throw JSONException("key"); val k = str(); ws(); if (s[i++] != ':') throw JSONException(":")
      o.map[k] = value(); ws(); when (s[i++]) { ',' -> continue; '}' -> return o; else -> throw JSONException("obj") } } }
  fun arr(): JSONArray { i++; val a = JSONArray(); ws(); if (s[i] == ']') { i++; return a }
    while (true) { a.list.add(value()); ws(); when (s[i++]) { ',' -> continue; ']' -> return a; else -> throw JSONException("arr") } } }
}

private fun q(s: String): String { val sb = StringBuilder("\"")
  for (c in s) when (c) { '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '/' -> sb.append("\\/")
    else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c) }
  return sb.append('"').toString() }
internal fun enc(v: Any?): String = when (v) { null, JSONObject.NULL -> "null"; is String -> q(v); is JSONObject -> v.toString(); is JSONArray -> v.toString(); else -> v.toString() }

class JSONObject() {
  val map = LinkedHashMap<String, Any?>()
  constructor(json: String) : this() {
    val p = P(json); val v = p.value(); p.ws(); if (v !is JSONObject || p.i != json.length) throw JSONException("not an object")
    map.putAll(v.map) }
  companion object { @JvmField val NULL: Any = object { override fun toString() = "null"; override fun equals(other: Any?) = other == null || other === this; override fun hashCode() = 0 } }
  fun put(k: String, v: Any?): JSONObject { if (v == null) map.remove(k) else map[k] = v; return this }
  fun put(k: String, v: Boolean): JSONObject { map[k] = v; return this }
  fun put(k: String, v: Long): JSONObject { map[k] = v; return this }
  fun put(k: String, v: Int): JSONObject { map[k] = v; return this }
  fun opt(k: String): Any? = map[k]
  fun has(k: String) = map.containsKey(k)
  fun isNull(k: String) = map[k] == null || map[k] === NULL
  fun optString(k: String): String = map[k]?.takeIf { it !== NULL }?.toString() ?: ""
  fun optBoolean(k: String): Boolean = map[k] as? Boolean ?: false
  fun optJSONObject(k: String) = map[k] as? JSONObject
  fun optJSONArray(k: String) = map[k] as? JSONArray
  fun getString(k: String): String = map[k]?.takeIf { it !== NULL }?.toString() ?: throw JSONException("no $k")
  fun getJSONObject(k: String) = map[k] as? JSONObject ?: throw JSONException("no $k")
  fun getJSONArray(k: String) = map[k] as? JSONArray ?: throw JSONException("no $k")
  override fun toString() = map.entries.joinToString(",", "{", "}") { q(it.key) + ":" + enc(it.value) }
}

class JSONArray() {
  internal val list = ArrayList<Any?>()
  fun put(v: Any?): JSONArray { list.add(v); return this }
  fun length() = list.size
  fun getJSONObject(i: Int) = list[i] as? JSONObject ?: throw JSONException("idx $i")
  override fun toString() = list.joinToString(",", "[", "]") { enc(it) }
}

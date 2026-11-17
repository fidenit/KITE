// 폰에서 도는 도구: 화면 고치기(screen)와 기억 보관함(note_*).
// 웹 검색·웹 읽기는 Anthropic 서버 쪽 도구라 여기서 실행하지 않는다 (Api.kt 가 요청에 함께 싣는다).
// 데스크톱 src/tools.rs 의 같은 이름 도구와 설명·동작을 맞춘다.
package com.fidenit.kite.engine

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import java.io.File

class PhoneTools(val vault: File) {
    /** 모델에게 주는 도구 정의. 순서와 내용이 바뀌면 프롬프트 캐시가 깨지니 함부로 바꾸지 않는다. */
    val definitions: List<Tool> = listOf(
        tool(
            "screen",
            "도구로 일하는 도중에 화면(보드)을 고친다. ops 에 화면 수정 명령을 한 줄에 하나씩 넣는다. 일하는 중간에 쓴 일반 텍스트는 사용자에게 보이지 않으므로, 진행 상황이나 중간 결과를 보여 주려면 반드시 이 도구를 쓴다. 거부된 명령이 있으면 이유가 돌아온다.",
            mapOf("ops" to prop("string", "화면 수정 명령들. 줄바꿈으로 구분.")),
            listOf("ops"),
        ),
        tool(
            "note_search",
            "기억 보관함의 모든 노트에서 글자를 찾는다. 사용자가 예전에 말한 것, 예전에 한 일을 떠올려야 할 때 쓴다.",
            mapOf("query" to prop("string")),
            listOf("query"),
        ),
        tool("note_read", "보관함의 노트 하나를 읽는다.", mapOf("path" to prop("string", "보관함 기준 경로. 예: 기억/사용자.md")), listOf("path")),
        tool(
            "note_write",
            "보관함에 노트를 만들거나 통째로 고쳐 쓴다. 오래 남길 사실은 기억/ 아래, 끝낸 일의 요약은 작업/ 아래에 둔다.",
            mapOf("path" to prop("string"), "content" to prop("string", "마크다운. 맨 위 속성에 description 한 줄을 넣는다.")),
            listOf("path", "content"),
        ),
        tool(
            "note_append",
            "보관함의 노트 끝에 내용을 덧붙인다 (없으면 만든다).",
            mapOf("path" to prop("string"), "content" to prop("string")),
            listOf("path", "content"),
        ),
    )

    private fun prop(type: String, description: String? = null): JsonValue =
        JsonValue.from(if (description == null) mapOf("type" to type) else mapOf("type" to type, "description" to description))

    private fun tool(name: String, description: String, props: Map<String, JsonValue>, required: List<String>): Tool =
        Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(Tool.InputSchema.Properties.builder().apply { props.forEach { (k, v) -> putAdditionalProperty(k, v) } }.build())
                    .required(required)
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build(),
            )
            .build()

    /** 진행 표시에 쓸 말 */
    fun describe(name: String, input: Map<String, Any?>): String {
        fun s(k: String) = input[k]?.toString().orEmpty().take(60)
        return when (name) {
            "note_search" -> "기억 찾기: ${s("query")}"
            "note_read" -> "기억 읽기: ${s("path")}"
            "note_write" -> "기억 쓰기: ${s("path")}"
            "note_append" -> "기억 덧붙이기: ${s("path")}"
            else -> name
        }
    }

    /** 보관함 도구를 실행한다. (결과, 오류인가) */
    fun call(name: String, input: Map<String, Any?>): Pair<String, Boolean> = try {
        fun arg(k: String): String = input[k]?.toString() ?: throw IllegalArgumentException("'$k' 가 없음")
        when (name) {
            "note_search" -> search(arg("query")) to false
            "note_read" -> {
                val f = file(arg("path"))
                if (!f.isFile) "오류: 노트가 없음: ${arg("path")}" to true else clip(f.readText()) to false
            }
            "note_write" -> {
                val f = file(arg("path"))
                f.parentFile?.mkdirs()
                f.writeText(arg("content"))
                "저장함: ${arg("path")}" to false
            }
            "note_append" -> {
                val f = file(arg("path"))
                f.parentFile?.mkdirs()
                val before = if (f.isFile) f.readText() else ""
                val gap = if (before.isEmpty() || before.endsWith("\n")) "" else "\n"
                val text = arg("content")
                f.appendText(gap + text + if (text.endsWith("\n")) "" else "\n")
                "덧붙임: ${arg("path")}" to false
            }
            else -> "오류: 이 폰에는 없는 도구: $name" to true
        }
    } catch (e: Exception) {
        "오류: ${e.message}" to true
    }

    /** 보관함 안의 파일. 보관함 밖을 가리키는 경로는 거부한다. */
    fun file(path: String): File {
        val clean = path.trim().replace('\\', '/').trimStart('/')
        require(clean.isNotEmpty()) { "경로가 비어 있음" }
        val root = vault.canonicalFile
        val f = File(root, clean).canonicalFile
        require(f.path.startsWith(root.path + File.separator)) { "보관함 밖의 경로: $path" }
        return f
    }

    private fun notes(): Sequence<File> =
        if (!vault.isDirectory) emptySequence() else vault.walkTopDown().filter { it.isFile && it.extension == "md" }

    private fun rel(f: File) = f.relativeTo(vault).path.replace('\\', '/')

    /** 낱말이 모두 들어 있는 줄을 찾는다 (최근에 고친 노트부터). */
    fun search(query: String): String {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "오류: 찾을 말이 비어 있음"
        val hits = mutableListOf<String>()
        for (f in notes().sortedByDescending { it.lastModified() }) {
            val path = rel(f)
            f.readLines().forEachIndexed { i, line ->
                val l = (path + " " + line).lowercase()
                if (words.all { l.contains(it) }) hits += "$path:${i + 1}: ${line.trim().take(200)}"
            }
        }
        if (hits.isEmpty()) return "찾은 것 없음: $query"
        return hits.take(40).joinToString("\n") + if (hits.size > 40) "\n… ${hits.size - 40}줄 더" else ""
    }

    /** 노트 목록 (경로 — 설명). 매 턴 `<memory>` 로 보낸다. */
    fun list(): String = notes()
        .filter { rel(it) != CORE }
        .sortedByDescending { it.lastModified() }
        .take(80)
        .joinToString("\n") { f ->
            val desc = f.useLines { lines ->
                lines.take(12).firstOrNull { it.trimStart().startsWith("description:") }?.substringAfter("description:")?.trim().orEmpty()
            }
            if (desc.isEmpty()) rel(f) else "${rel(f)} — $desc"
        }

    /** 핵심 기억: 사용자에 대해 늘 알고 있어야 할 사실. 매 턴 `<core>` 로 보낸다. */
    fun core(): String = File(vault, CORE).takeIf { it.isFile }?.readText()?.trim().orEmpty()

    private fun clip(text: String, max: Int = 40_000) = if (text.length <= max) text else text.take(max) + "\n…(${text.length - max}자 생략)"

    companion object {
        const val CORE = "기억/핵심.md"
    }
}

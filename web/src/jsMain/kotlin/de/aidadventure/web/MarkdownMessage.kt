package de.aidadventure.web

import react.FC
import react.Props
import kotlin.js.JsNonModule
import kotlin.js.unsafeCast

@JsModule("react-markdown")
@JsNonModule
external val reactMarkdownModule: dynamic

@JsModule("remark-gfm")
@JsNonModule
external val remarkGfmModule: dynamic

external interface ReactMarkdownProps : Props {
    var children: String
    var remarkPlugins: Array<dynamic>
    var skipHtml: Boolean
}

external interface MarkdownMessageProps : Props {
    var content: String
}

internal val MarkdownMessage = FC<MarkdownMessageProps> { props ->
    val reactMarkdown = reactMarkdownModule.default.unsafeCast<FC<ReactMarkdownProps>>()
    reactMarkdown {
        children = props.content
        remarkPlugins = arrayOf(remarkGfmModule.default)
        skipHtml = true
    }
}

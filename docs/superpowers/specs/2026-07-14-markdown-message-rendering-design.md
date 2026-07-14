# Markdown Message Rendering Design

## Goal

Render the content of both player and game-master transcript messages as
GitHub-Flavored Markdown (GFM), without treating any embedded HTML as active
DOM content.

## Approach

Add `react-markdown` and `remark-gfm` to the Kotlin/JS web module. A focused
`MarkdownMessage` React component will turn a message string into React nodes
and replace the current plain-text paragraph in the transcript for both roles.

The renderer will not enable `rehype-raw` or any equivalent HTML parsing path.
Raw HTML in user input or LLM output remains inert text; it cannot create
elements, execute scripts, or change the page structure. Links are rendered
only through the renderer's safe default protocol handling.

## Rendering Behavior

Supported Markdown includes headings, emphasis, lists, links, block quotes,
inline code, fenced code blocks, tables, and task lists. Styles will preserve
the existing journal appearance while adding readable spacing, code treatment,
table overflow handling, and clear link affordances.

The same component is used for user and LLM messages, so persisted history and
streamed transient messages render consistently. A partially streamed Markdown
construct can display as plain text until its closing syntax arrives; the next
stream update re-renders it from the complete accumulated string.

## Testing

Browser tests will verify that representative GFM content creates the expected
safe DOM structure and that raw HTML is not interpreted. Existing transcript
tests will be updated only where the rendered structure deliberately changes.

## Non-Goals

- No server-side Markdown processing or persistence schema change.
- No HTML rendering, custom Markdown extensions, or syntax highlighting in
  this change.
- No change to prompt construction or the treatment of LLM output as
  untrusted input.

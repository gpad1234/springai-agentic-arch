import { useRef, useState } from 'react'
import './App.css'

const SUGGESTIONS = [
  'Explain Spring AI in one paragraph',
  'Help me design a REST API',
  'Give me a thoughtful writing prompt',
]

function App() {
  const [messages, setMessages] = useState([
    { role: 'assistant', text: 'Hello. What would you like to explore?' },
  ])
  const [prompt, setPrompt] = useState('')
  const [sending, setSending] = useState(false)
  const conversationId = useRef(crypto.randomUUID())

  async function sendPrompt(text) {
    setMessages((prev) => [
      ...prev,
      { role: 'user', text },
      { role: 'assistant', text: 'Claude is thinking...', pending: true },
    ])
    setSending(true)

    try {
      const response = await fetch('/api/claude/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt: text, conversationId: conversationId.current }),
      })
      if (!response.ok) throw new Error('The chat request failed.')
      const data = await response.json()
      setMessages((prev) =>
        replaceLastPending(prev, data.response || 'Claude returned an empty response.'),
      )
    } catch (error) {
      setMessages((prev) => replaceLastPending(prev, error.message))
    } finally {
      setSending(false)
    }
  }

  function replaceLastPending(prev, text) {
    const next = [...prev]
    const lastIndex = next.length - 1
    next[lastIndex] = { role: 'assistant', text }
    return next
  }

  function handleSubmit(event) {
    event.preventDefault()
    const text = prompt.trim()
    if (!text || sending) return
    setPrompt('')
    sendPrompt(text)
  }

  function handleKeyDown(event) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      handleSubmit(event)
    }
  }

  return (
    <div className="shell">
      <header>
        <div>
          <p className="eyebrow">Spring AI / Anthropic</p>
          <h1>Claude Studio</h1>
        </div>
        <div className="status">Connected to your local agent</div>
      </header>

      <main>
        <section className="conversation" aria-label="Conversation">
          <div className="conversation-bar">
            <span>Conversation</span>
            <span>{messages.length} message{messages.length === 1 ? '' : 's'}</span>
          </div>
          <div className="messages" aria-live="polite">
            {messages.map((message, index) => (
              <div
                key={index}
                className={`message ${message.role}${message.pending ? ' pending' : ''}`}
              >
                {message.text}
              </div>
            ))}
          </div>
          <form className="composer" onSubmit={handleSubmit}>
            <textarea
              value={prompt}
              onChange={(event) => setPrompt(event.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="Ask Claude anything..."
              aria-label="Message for Claude"
              disabled={sending}
              required
            />
            <button type="submit" disabled={sending}>Send</button>
          </form>
        </section>

        <aside className="prompt-panel">
          <h2>Start somewhere.</h2>
          <p>Use the Anthropic Claude model through a simple Spring AI conversation.</p>
          <div className="suggestions">
            {SUGGESTIONS.map((suggestion) => (
              <button
                key={suggestion}
                className="suggestion"
                type="button"
                onClick={() => setPrompt(suggestion)}
              >
                {suggestion}
              </button>
            ))}
          </div>
        </aside>
      </main>
    </div>
  )
}

export default App

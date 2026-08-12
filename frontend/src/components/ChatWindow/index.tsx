import {
  ArrowRightOutlined,
  BulbOutlined,
  CodeOutlined,
  DatabaseOutlined,
} from '@ant-design/icons'
import { Empty } from 'antd'
import { useEffect, useRef } from 'react'
import type { ChatMessage } from '../../api/types'
import MessageBubble from '../MessageBubble'

const suggestions = [
  {
    icon: <CodeOutlined />,
    title: '解释 ConcurrentHashMap',
    text: '解释 ConcurrentHashMap 在 JDK 8 中的并发控制机制',
  },
  {
    icon: <DatabaseOutlined />,
    title: 'Redis 缓存治理',
    text: '缓存穿透、击穿和雪崩分别如何解决？',
  },
  {
    icon: <BulbOutlined />,
    title: '分析 RAG 链路',
    text: '为什么 Hybrid Retrieval 要使用 RRF 融合？',
  },
]

interface ChatWindowProps {
  messages: ChatMessage[]
  onSuggestion: (question: string) => void
}

export default function ChatWindow({
  messages,
  onSuggestion,
}: ChatWindowProps) {
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({
      behavior: 'smooth',
      block: 'end',
    })
  }, [messages])

  return (
    <div className="chat-window">
      {messages.length === 0 ? (
        <div className="chat-empty">
          <div className="chat-empty__orb">
            <DatabaseOutlined />
          </div>
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={false}
          />
          <h2>从企业知识中获得可靠答案</h2>
          <p>
            混合检索定位依据，BGE 精排提升相关性，
            每条回答都可追溯来源。
          </p>
          <div className="suggestion-grid">
            {suggestions.map((item) => (
              <button
                type="button"
                key={item.title}
                className="suggestion-card"
                onClick={() => onSuggestion(item.text)}
              >
                <span className="suggestion-icon">{item.icon}</span>
                <span>
                  <strong>{item.title}</strong>
                  <small>{item.text}</small>
                </span>
                <ArrowRightOutlined />
              </button>
            ))}
          </div>
        </div>
      ) : (
        <div className="message-list">
          {messages.map((message) => (
            <MessageBubble key={message.id} message={message} />
          ))}
          <div ref={bottomRef} />
        </div>
      )}
    </div>
  )
}

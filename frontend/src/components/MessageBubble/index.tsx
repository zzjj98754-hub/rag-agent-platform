import {
  RobotOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { Avatar, Spin } from 'antd'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import type { ChatMessage } from '../../api/types'
import CitationCard from '../CitationCard'

interface MessageBubbleProps {
  message: ChatMessage
}

export default function MessageBubble({
  message,
}: MessageBubbleProps) {
  const assistant = message.role === 'assistant'
  return (
    <div
      className={`message-row ${
        assistant ? 'message-row--assistant' : 'message-row--user'
      }`}
    >
      <Avatar
        className="message-avatar"
        icon={assistant ? <RobotOutlined /> : <UserOutlined />}
      />
      <div className="message-stack">
        <div className="message-label">
          {assistant ? '知识库助手' : '你'}
          {message.streaming && (
            <span className="streaming-label">
              <i /> 实时生成中
            </span>
          )}
        </div>
        <div className="message-bubble">
          {message.content ? (
            <div className="markdown-body">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>
                {message.content}
              </ReactMarkdown>
              {message.streaming && (
                <span className="token-cursor" aria-hidden />
              )}
            </div>
          ) : (
            <div className="thinking-state">
              <Spin size="small" />
              <span>正在组织知识与生成回答...</span>
            </div>
          )}
        </div>
        {assistant &&
          Boolean(message.citations?.length) && (
            <section className="citations-section">
              <div className="section-kicker">参考资料</div>
              <div className="citation-grid">
                {message.citations?.map((citation, index) => (
                  <CitationCard
                    key={`${citation.chunkId}-${index}`}
                    citation={citation}
                    index={index}
                  />
                ))}
              </div>
            </section>
          )}
      </div>
    </div>
  )
}

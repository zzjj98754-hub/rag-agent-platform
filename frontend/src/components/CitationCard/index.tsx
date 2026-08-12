import {
  FileTextOutlined,
  LinkOutlined,
} from '@ant-design/icons'
import { Progress, Tooltip } from 'antd'
import type { Citation } from '../../api/types'

interface CitationCardProps {
  citation: Citation
  index: number
}

export default function CitationCard({
  citation,
  index,
}: CitationCardProps) {
  const score = Math.round(
    Math.max(0, Math.min(1, citation.score)) * 100,
  )
  return (
    <article className="citation-card">
      <div className="citation-card__head">
        <span className="citation-index">{index + 1}</span>
        <FileTextOutlined />
        <Tooltip title={citation.chunkId}>
          <strong>{citation.title}</strong>
        </Tooltip>
        <LinkOutlined className="citation-link-icon" />
      </div>
      {citation.preview && (
        <p className="citation-preview">{citation.preview}</p>
      )}
      <div className="citation-score">
        <span>匹配度</span>
        <Progress
          percent={score}
          size="small"
          showInfo={false}
          strokeColor="#3157d5"
        />
        <strong>{score}%</strong>
      </div>
    </article>
  )
}

import {
  CheckCircleFilled,
  ClockCircleOutlined,
  LoadingOutlined,
  NodeIndexOutlined,
} from '@ant-design/icons'
import { Tag } from 'antd'
import type {
  RagTimings,
  RagTraceData,
  TraceStep,
} from '../../api/types'

export const emptyTimings: RagTimings = {
  retrievalMs: 0,
  bm25Ms: 0,
  embeddingMs: 0,
  rerankMs: 0,
  llmMs: 0,
  totalMs: 0,
}

export const initialTrace: RagTraceData = {
  steps: [
    'Query Rewrite',
    'BM25 Recall',
    'Vector Recall',
    'RRF Fusion',
    'BGE Rerank',
    'Generate',
  ].map((label, index) => ({
    key: `step-${index}`,
    label,
    status: 'pending',
    durationMs: 0,
  })),
  timings: emptyTimings,
}

interface RagTraceProps {
  trace: RagTraceData
  phase: string
  message: string
  isStreaming: boolean
}

function statusIcon(step: TraceStep) {
  if (step.status === 'completed') {
    return <CheckCircleFilled className="trace-icon--done" />
  }
  if (step.status === 'running') {
    return <LoadingOutlined className="trace-icon--running" />
  }
  return <span className="trace-icon--pending" />
}

export default function RagTrace({
  trace,
  phase,
  message,
  isStreaming,
}: RagTraceProps) {
  const timings = trace.timings ?? emptyTimings
  return (
    <aside className="rag-trace">
      <div className="rag-trace__header">
        <div>
          <span className="eyebrow">Explainability</span>
          <h3>
            <NodeIndexOutlined /> RAG Pipeline
          </h3>
        </div>
        <Tag color={isStreaming ? 'processing' : 'default'}>
          {isStreaming ? 'LIVE' : 'READY'}
        </Tag>
      </div>

      <div className="trace-status">
        <span className={`phase-dot phase-dot--${phase || 'idle'}`} />
        <div>
          <strong>{phase === 'generating' ? '生成阶段' : '检索阶段'}</strong>
          <p>{message || '等待新的问答请求'}</p>
        </div>
      </div>

      <div className="trace-steps">
        {trace.steps.map((step, index) => (
          <div className="trace-step" key={step.key}>
            <div className="trace-step__rail">
              {statusIcon(step)}
              {index < trace.steps.length - 1 && <i />}
            </div>
            <div className="trace-step__content">
              <strong>{step.label}</strong>
              <span>
                {step.status === 'pending'
                  ? '等待'
                  : step.durationMs > 0
                    ? `${step.durationMs} ms`
                    : '完成'}
              </span>
            </div>
          </div>
        ))}
      </div>

      <div className="timing-panel">
        <div className="timing-title">
          <ClockCircleOutlined /> 阶段耗时
        </div>
        <div className="timing-grid">
          <div>
            <span>Retrieval</span>
            <strong>{timings.retrievalMs} ms</strong>
          </div>
          <div>
            <span>Rerank</span>
            <strong>{timings.rerankMs} ms</strong>
          </div>
          <div>
            <span>LLM</span>
            <strong>{timings.llmMs} ms</strong>
          </div>
          <div>
            <span>Total</span>
            <strong>{timings.totalMs} ms</strong>
          </div>
        </div>
      </div>
    </aside>
  )
}

import {
  CloudUploadOutlined,
  DeleteOutlined,
  FileMarkdownOutlined,
  FilePdfOutlined,
  FileTextOutlined,
  ReloadOutlined,
  SafetyOutlined,
  ReloadOutlined as RetryOutlined,
} from '@ant-design/icons'
import {
  Alert,
  App,
  Button,
  Card,
  Empty,
  Popconfirm,
  Space,
  Table,
  Tag,
  Typography,
  Upload,
  type TableProps,
  type UploadProps,
} from 'antd'
import { useCallback, useEffect, useState } from 'react'
import {
  deleteDocument,
  listDocuments,
  uploadDocumentAsync,
  getIndexTaskStatus,
  retryIndexTask,
} from '../../api/document'
import type { DocumentItem, IndexTaskStatus } from '../../api/types'
import { useAuth } from '../../store/AuthContext'
import { errorMessage } from '../../utils/error'

const statusColor: Record<string, string> = {
  INDEXED: 'success',
  PROCESSING: 'processing',
  FAILED: 'error',
  READY: 'blue',
  NOT_LOADED: 'default',
}

function fileIcon(title: string) {
  if (title.toLowerCase().endsWith('.pdf')) {
    return <FilePdfOutlined className="file-icon file-icon--pdf" />
  }
  if (title.toLowerCase().endsWith('.md')) {
    return <FileMarkdownOutlined className="file-icon file-icon--md" />
  }
  return <FileTextOutlined className="file-icon" />
}

export default function KnowledgePage() {
  const { message } = App.useApp()
  const { user } = useAuth()
  const [documents, setDocuments] = useState<DocumentItem[]>([])
  const [loading, setLoading] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [tasks, setTasks] = useState<IndexTaskStatus[]>([])

  const persistTasks = (next: IndexTaskStatus[]) => {
    setTasks(next)
    localStorage.setItem('demo00.indexTasks', JSON.stringify(next.filter((task) => !['SUCCEEDED'].includes(task.status))))
  }

  const refresh = useCallback(async () => {
    if (user?.role !== 'ADMIN') return
    setLoading(true)
    try {
      const result = await listDocuments()
      setDocuments(result.documents)
    } catch (error) {
      message.error(errorMessage(error, '文档列表加载失败'))
    } finally {
      setLoading(false)
    }
  }, [message, user?.role])

  const pollTasks = useCallback(async () => {
    const stored = JSON.parse(localStorage.getItem('demo00.indexTasks') || '[]') as IndexTaskStatus[]
    if (!stored.length) return
    const next = await Promise.all(stored.map(async (task) => {
      try { return await getIndexTaskStatus(task.taskId) } catch { return task }
    }))
    persistTasks(next)
    if (next.some((task) => ['SUCCEEDED', 'FAILED', 'DEAD'].includes(task.status))) await refresh()
  }, [refresh])

  useEffect(() => {
    void refresh()
    void pollTasks()
    const timer = window.setInterval(() => void pollTasks(), 3000)
    return () => window.clearInterval(timer)
  }, [refresh, pollTasks])

  const uploadProps: UploadProps = {
    accept: '.pdf,.md,.txt',
    multiple: false,
    showUploadList: false,
    disabled: uploading || user?.role !== 'ADMIN',
    customRequest: async ({ file, onSuccess, onError }) => {
      setUploading(true)
      try {
        const selected = file as File
        const idempotencyKey = `knowledge-upload:${selected.name}:${selected.size}:${selected.lastModified}`
        const result = await uploadDocumentAsync(selected, idempotencyKey)
        if (!result.success) throw new Error('文档任务创建失败')
        const initial: IndexTaskStatus = { taskId: result.taskId, status: 'PENDING', processedChunks: 0, totalChunks: 0, retryCount: 0 }
        persistTasks([...tasks.filter((task) => task.taskId !== initial.taskId), initial])
        message.success(`${result.fileName} 已进入异步索引队列`)
        onSuccess?.(result)
        await refresh()
      } catch (error) {
        message.error(errorMessage(error, '文档上传失败'))
        onError?.(
          error instanceof Error ? error : new Error('上传失败'),
        )
      } finally {
        setUploading(false)
      }
    },
  }

  const taskRows = tasks.map((task) => ({ ...task, title: `任务 ${task.taskId.slice(0, 8)}` }))

  const columns: TableProps<DocumentItem>['columns'] = [
    {
      title: '文档',
      dataIndex: 'title',
      key: 'title',
      render: (title: string, record) => (
        <div className="document-name">
          {fileIcon(title)}
          <div>
            <strong>{title}</strong>
            <span>{record.filePath}</span>
          </div>
        </div>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 116,
      render: (status: string) => (
        <Tag color={statusColor[status]}>{status}</Tag>
      ),
    },
    {
      title: 'Chunk',
      dataIndex: 'chunkCount',
      width: 96,
      render: (count: number) => (
        <span className="metric-value">{count}</span>
      ),
    },
    {
      title: 'Vector',
      dataIndex: 'vectorCount',
      width: 96,
      render: (count: number) => (
        <span className="metric-value">{count}</span>
      ),
    },
    {
      title: 'Embedding',
      key: 'embedding',
      width: 180,
      render: (_, record) => (
        <Space size={4} wrap>
          <Tag color={statusColor[record.embeddingStatus]}>
            {record.embeddingStatus}
          </Tag>
          {record.embeddingStatus === 'READY' && (
            <Tag color={
              record.embeddingSource === 'siliconflow'
                ? 'purple'
                : 'cyan'
            }>
              {record.embeddingSource}
            </Tag>
          )}
        </Space>
      ),
    },
    {
      title: '创建时间',
      dataIndex: 'createTime',
      width: 180,
      render: (value: string) =>
        value
          ? new Date(value).toLocaleString('zh-CN', {
              hour12: false,
            })
          : '-',
    },
    {
      title: '',
      key: 'action',
      width: 64,
      render: (_, record) => (
        <Popconfirm
          title="删除该文档？"
          description="会同步移除 BM25 与向量索引中的 Chunk。"
          okText="删除"
          cancelText="取消"
          okButtonProps={{ danger: true }}
          onConfirm={async () => {
            try {
              await deleteDocument(record.id)
              message.success('文档已删除')
              await refresh()
            } catch (error) {
              message.error(errorMessage(error, '删除失败'))
            }
          }}
        >
          <Button
            danger
            type="text"
            icon={<DeleteOutlined />}
            aria-label={`删除 ${record.title}`}
          />
        </Popconfirm>
      ),
    },
  ]

  if (user?.role !== 'ADMIN') {
    return (
      <div className="page">
        <header className="page-header">
          <div>
            <h1>知识库管理</h1>
            <p>文档入库与索引状态</p>
          </div>
        </header>
        <Alert
          showIcon
          type="warning"
          icon={<SafetyOutlined />}
          message="需要 ADMIN 权限"
          description="知识库上传和删除属于管理操作，请使用管理员账号登录。"
        />
      </div>
    )
  }

  return (
    <div className="page knowledge-page">
      <header className="page-header">
        <div>
          <span className="eyebrow">Knowledge ingestion</span>
          <h1>知识库管理</h1>
          <p>查看文档状态并触发 Chunk → BM25 → Embedding → Vector</p>
        </div>
        <Button
          icon={<ReloadOutlined />}
          loading={loading}
          onClick={() => void refresh()}
        >
          刷新
        </Button>
      </header>

      <div className="knowledge-grid">
        <Card className="upload-card" bordered={false}>
          <div className="upload-card__title">
            <CloudUploadOutlined />
            <div>
              <h3>上传知识文档</h3>
              <span>自动完成解析、切分与索引</span>
            </div>
          </div>
          <Upload.Dragger {...uploadProps} className="document-dragger">
            <p className="ant-upload-drag-icon">
              <CloudUploadOutlined />
            </p>
            <p className="ant-upload-text">
              拖拽文件到这里，或点击选择
            </p>
            <p className="ant-upload-hint">
              支持 PDF、Markdown、TXT，单文件不超过 10 MB
            </p>
          </Upload.Dragger>
          <div className="ingestion-flow">
            {['Load', 'Chunk', 'BM25', 'Embedding', 'Vector'].map(
              (step, index) => (
                <div key={step}>
                  <span>{index + 1}</span>
                  {step}
                </div>
              ),
            )}
          </div>
        </Card>

        {taskRows.length > 0 && <Card title="异步索引任务" bordered={false}>
          <Table
            rowKey="taskId"
            dataSource={taskRows}
            pagination={false}
            columns={[
              { title: 'Task', dataIndex: 'title' },
              { title: '状态', dataIndex: 'status', render: (status: string) => <Tag color={statusColor[status] || 'default'}>{status}</Tag> },
              { title: 'Chunk', render: (_: unknown, task: IndexTaskStatus) => `${task.processedChunks}/${task.totalChunks || '?'}` },
              { title: '失败原因', dataIndex: 'failureReason', render: (value?: string) => value || '-' },
              { title: '', render: (_: unknown, task: IndexTaskStatus) => ['FAILED', 'DEAD'].includes(task.status) && <Button size="small" icon={<RetryOutlined />} onClick={async () => { await retryIndexTask(task.taskId); await pollTasks() }}>重试</Button> },
            ]}
          />
        </Card>}

        <Card className="document-table-card" bordered={false}>
          <div className="card-heading">
            <div>
              <Typography.Title level={4}>文档列表</Typography.Title>
              <span>共 {documents.length} 篇知识文档</span>
            </div>
            <Space>
              <Tag color="blue">BM25</Tag>
              <Tag color="purple">Vector</Tag>
            </Space>
          </div>
          <Table<DocumentItem>
            rowKey="id"
            columns={columns}
            dataSource={documents}
            loading={loading}
            pagination={{ pageSize: 8, hideOnSinglePage: true }}
            locale={{
              emptyText: (
                <Empty description="还没有文档，先上传一篇知识资料" />
              ),
            }}
            scroll={{ x: 860 }}
          />
        </Card>
      </div>
    </div>
  )
}

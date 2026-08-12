import {
  CloudUploadOutlined,
  DeleteOutlined,
  FileMarkdownOutlined,
  FilePdfOutlined,
  FileTextOutlined,
  ReloadOutlined,
  SafetyOutlined,
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
  uploadDocument,
} from '../../api/document'
import type { DocumentItem } from '../../api/types'
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

  useEffect(() => {
    void refresh()
  }, [refresh])

  const uploadProps: UploadProps = {
    accept: '.pdf,.md,.txt',
    multiple: false,
    showUploadList: false,
    disabled: uploading || user?.role !== 'ADMIN',
    customRequest: async ({ file, onSuccess, onError }) => {
      setUploading(true)
      try {
        const result = await uploadDocument(file as File)
        if (!result.success) {
          throw new Error(
            result.failed[0]?.reason || '文档入库失败',
          )
        }
        message.success(
          `${result.fileName} 已完成入库，共 ${result.totalChunks} 个 Chunk`,
        )
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

import {
  BookOutlined,
  CheckCircleOutlined,
  ExclamationCircleOutlined,
  SendOutlined,
} from '@ant-design/icons'
import {
  Alert,
  App,
  Button,
  Card,
  Collapse,
  Empty,
  Form,
  Input,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  askPrinter,
  createAfterSales,
  listPrinterProducts,
} from '../../api/printer'
import type { PrinterProduct, PrinterQaResponse } from '../../api/types'

const { TextArea } = Input

export default function PrinterAssistantPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [products, setProducts] = useState<PrinterProduct[]>([])
  const [productId, setProductId] = useState<number>()
  const [question, setQuestion] = useState('')
  const [answer, setAnswer] = useState<PrinterQaResponse>()
  const [loading, setLoading] = useState(true)
  const [asking, setAsking] = useState(false)
  const [applying, setApplying] = useState(false)
  const [applicationNote, setApplicationNote] = useState('')
  const [loadError, setLoadError] = useState('')
  const applicationKey = useRef(crypto.randomUUID())

  useEffect(() => {
    listPrinterProducts()
      .then((items) => {
        setProducts(items)
        if (items[0]) setProductId(items[0].id)
      })
      .catch(() => setLoadError('型号加载失败，请确认管理员已初始化演示资料'))
      .finally(() => setLoading(false))
  }, [])

  const selectedProduct = useMemo(
    () => products.find((item) => item.id === productId),
    [products, productId],
  )

  const submitQuestion = async () => {
    if (!productId || !question.trim()) return
    setAsking(true)
    setAnswer(undefined)
    applicationKey.current = crypto.randomUUID()
    try {
      setAnswer(await askPrinter(productId, question.trim()))
    } catch {
      message.error('问答失败，请稍后重试')
    } finally {
      setAsking(false)
    }
  }

  const submitApplication = async () => {
    if (!answer || !productId) return
    setApplying(true)
    try {
      const created = await createAfterSales(
        {
          productId,
          question,
          troubleshootingSteps: answer.answer,
          additionalNote: applicationNote,
        },
        applicationKey.current,
      )
      message.success(`申请已提交：${created.applicationNo}`)
      navigate(`/applications/${created.id}`)
    } catch {
      message.error('提交失败，请检查网络后重试')
    } finally {
      setApplying(false)
    }
  }

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">Printer After-sales Demo</span>
          <h1>打印机售后助手</h1>
          <p>仅依据所选虚构型号的已索引手册与售后政策回答。</p>
        </div>
        <Space>
          <Button onClick={() => navigate('/my-applications')}>我的售后申请</Button>
        </Space>
      </header>

      {loadError && <Alert type="warning" showIcon message={loadError} description="请让管理员点击管理处理页中的“初始化演示资料”。" />}
      <div className="printer-grid">
        <Card title="1 · 选择型号并描述故障" className="printer-card">
          {loading ? <Spin tip="正在加载型号..." /> : products.length === 0 ? <Empty description="暂无可用型号" /> : (
            <>
              <Select
                value={productId}
                onChange={(value) => { setProductId(value); setAnswer(undefined) }}
                options={products.map((item) => ({ label: item.modelName, value: item.id }))}
                style={{ width: '100%', marginBottom: 14 }}
                aria-label="打印机型号"
              />
              {selectedProduct && <div className="model-hint"><Tag color="blue">{selectedProduct.productCode}</Tag>{selectedProduct.description}</div>}
              <TextArea
                value={question}
                onChange={(event) => setQuestion(event.target.value)}
                placeholder="例如：打印机连不上 Wi-Fi，应该怎么排查？"
                autoSize={{ minRows: 5, maxRows: 9 }}
                maxLength={2000}
                showCount
              />
              <Button type="primary" icon={<SendOutlined />} loading={asking} disabled={!question.trim()} onClick={submitQuestion} style={{ marginTop: 14 }}>
                开始排查
              </Button>
            </>
          )}
        </Card>

        <Card title="2 · 助手回答" className="printer-card answer-card">
          {!answer && <Empty description={asking ? '正在检索型号资料并生成回答...' : '提交问题后显示排查步骤与资料出处'} />}
          {answer && (
            <>
              <Alert
                type={answer.insufficientEvidence ? 'warning' : 'success'}
                showIcon
                icon={answer.insufficientEvidence ? <ExclamationCircleOutlined /> : <CheckCircleOutlined />}
                message={answer.insufficientEvidence ? '资料不足' : `已基于 ${answer.product.modelName} 的资料回答`}
                description={<Typography.Paragraph copyable={{ text: answer.answer }} style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>{answer.answer}</Typography.Paragraph>}
              />
              <Typography.Title level={5} style={{ marginTop: 20 }}><BookOutlined /> 资料出处（可展开原文片段）</Typography.Title>
              {answer.citations.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有可展示的依据" /> : (
                <Collapse items={answer.citations.map((citation, index) => ({
                  key: citation.chunkId,
                  label: `[${index + 1}] ${citation.title}`,
                  children: <Typography.Paragraph copyable={{ text: citation.snippet }} style={{ whiteSpace: 'pre-wrap' }}>{citation.snippet}</Typography.Paragraph>,
                }))} />
              )}
              {!answer.insufficientEvidence && (
                <div className="answer-actions">
                  <Button icon={<CheckCircleOutlined />} onClick={() => message.success('已记录本次排查结果')}>已解决</Button>
                  <Button type="primary" danger onClick={() => document.getElementById('after-sales-form')?.scrollIntoView({ behavior: 'smooth' })}>未解决，申请售后</Button>
                </div>
              )}
            </>
          )}
        </Card>
      </div>

      {answer && !answer.insufficientEvidence && (
        <Card id="after-sales-form" title="3 · 未解决？提交售后申请" className="printer-card application-card">
          <p className="muted-text">申请会保存型号、原问题和本次已确认的排查记录；可补充现场现象。重复点击使用同一请求幂等保护。</p>
          <TextArea value={applicationNote} onChange={(event) => setApplicationNote(event.target.value)} placeholder="补充说明（可选），例如：清洁和校准后仍然模糊" maxLength={2000} autoSize={{ minRows: 3, maxRows: 6 }} />
          <Button type="primary" danger loading={applying} onClick={submitApplication} style={{ marginTop: 14 }}>提交售后申请</Button>
        </Card>
      )}
    </div>
  )
}

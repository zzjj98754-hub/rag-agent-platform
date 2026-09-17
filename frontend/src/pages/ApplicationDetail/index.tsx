import { ArrowLeftOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Descriptions, Divider, Empty, Spin, Tag, Typography } from 'antd'
import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { getApplication } from '../../api/printer'
import type { AfterSalesApplication } from '../../api/types'

const statusLabels = { PENDING: '待处理', PROCESSING: '处理中', COMPLETED: '已完成' }

export default function ApplicationDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const [item, setItem] = useState<AfterSalesApplication>()
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  useEffect(() => { if (id) getApplication(id).then(setItem).catch(() => setError('申请不存在，或当前用户无权查看')).finally(() => setLoading(false)) }, [id])
  return <div className="page"><header className="page-header"><div><span className="eyebrow">Request Detail</span><h1>申请详情</h1></div><Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/my-applications')}>返回列表</Button></header>
    {loading && <Spin tip="正在加载详情..." />}{error && <Alert type="error" message={error} />}{!loading && !error && !item && <Empty description="暂无数据" />}
    {item && <Card title={<span>{item.applicationNo} <Tag color={item.status === 'COMPLETED' ? 'green' : item.status === 'PROCESSING' ? 'blue' : 'gold'}>{statusLabels[item.status]}</Tag></span>}><Descriptions bordered column={1} items={[{ key: 'model', label: '型号', children: item.modelName }, { key: 'question', label: '问题描述', children: item.question }, { key: 'steps', label: '已确认排查记录', children: <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>{item.troubleshootingSteps}</Typography.Paragraph> }, { key: 'note', label: '补充说明', children: item.additionalNote || '无' }, { key: 'time', label: '更新时间', children: new Date(item.updateTime).toLocaleString() }]} /><Divider /><Typography.Title level={5}>处理说明</Typography.Title><Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>{item.processingNote || '尚无处理说明，当前状态为“' + statusLabels[item.status] + '”。'}</Typography.Paragraph></Card>}
  </div>
}

import { Alert, Button, Card, Empty, List, Spin, Tag } from 'antd'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { listMyApplications } from '../../api/printer'
import type { AfterSalesApplication } from '../../api/types'

const statusLabels: Record<AfterSalesApplication['status'], string> = { PENDING: '待处理', PROCESSING: '处理中', COMPLETED: '已完成' }

export default function MyApplicationsPage() {
  const navigate = useNavigate()
  const [items, setItems] = useState<AfterSalesApplication[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  useEffect(() => { listMyApplications().then(setItems).catch(() => setError('申请加载失败，请稍后重试')).finally(() => setLoading(false)) }, [])
  return <div className="page"><header className="page-header"><div><span className="eyebrow">My Requests</span><h1>我的售后申请</h1><p>仅展示当前登录用户提交的申请。</p></div><Button onClick={() => navigate('/printer-assistant')}>返回问答</Button></header>
    {error && <Alert type="error" message={error} />}
    <Card>{loading ? <Spin tip="正在加载申请..." /> : items.length === 0 ? <Empty description="还没有售后申请" /> : <List dataSource={items} renderItem={(item) => <List.Item actions={[<Button key="detail" type="link" onClick={() => navigate(`/applications/${item.id}`)}>查看详情</Button>]}><List.Item.Meta title={<span>{item.applicationNo} <Tag color={item.status === 'COMPLETED' ? 'green' : item.status === 'PROCESSING' ? 'blue' : 'gold'}>{statusLabels[item.status]}</Tag></span>} description={`${item.modelName} · ${new Date(item.updateTime).toLocaleString()}`} /></List.Item>} />}</Card>
  </div>
}

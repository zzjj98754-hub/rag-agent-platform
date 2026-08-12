import {
  ApiOutlined,
  BookOutlined,
  DatabaseOutlined,
  LogoutOutlined,
  MessageOutlined,
  RobotOutlined,
} from '@ant-design/icons'
import { Avatar, Button, Layout, Menu, Tag, Typography } from 'antd'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../../store/AuthContext'

const { Sider, Content } = Layout

const navigation = [
  {
    key: '/chat',
    icon: <MessageOutlined />,
    label: '智能问答',
  },
  {
    key: '/knowledge',
    icon: <DatabaseOutlined />,
    label: '知识库',
  },
  {
    key: '/agent',
    icon: <ApiOutlined />,
    label: 'Agent 调试',
  },
]

export default function AppShell() {
  const navigate = useNavigate()
  const location = useLocation()
  const { user, logout } = useAuth()

  return (
    <Layout className="app-layout">
      <Sider width={232} className="app-sider" breakpoint="lg">
        <div className="brand-block">
          <div className="brand-mark">
            <RobotOutlined />
          </div>
          <div>
            <Typography.Text className="brand-name">
              RAG Intelligence
            </Typography.Text>
            <span className="brand-subtitle">Knowledge Agent</span>
          </div>
        </div>

        <div className="nav-caption">工作台</div>
        <Menu
          mode="inline"
          selectedKeys={[location.pathname]}
          items={navigation}
          onClick={({ key }) => navigate(key)}
          className="app-menu"
        />

        <div className="platform-note">
          <BookOutlined />
          <div>
            <strong>Hybrid RAG</strong>
            <span>BM25 · Vector · Rerank</span>
          </div>
        </div>

        <div className="user-panel">
          <Avatar size={36}>
            {user?.username.slice(0, 1).toUpperCase()}
          </Avatar>
          <div className="user-meta">
            <strong>{user?.username}</strong>
            <Tag color={user?.role === 'ADMIN' ? 'blue' : 'default'}>
              {user?.role}
            </Tag>
          </div>
          <Button
            type="text"
            icon={<LogoutOutlined />}
            aria-label="退出登录"
            onClick={() => {
              logout()
              navigate('/login')
            }}
          />
        </div>
      </Sider>
      <Layout>
        <Content className="app-content">
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}

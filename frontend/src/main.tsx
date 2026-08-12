import React from 'react'
import ReactDOM from 'react-dom/client'
import { App as AntApp, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import App from './App'
import { AuthProvider } from './store/AuthContext'
import './styles/global.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#3157d5',
          colorInfo: '#3157d5',
          colorSuccess: '#178f68',
          borderRadius: 10,
          fontFamily:
            'Inter, "SF Pro Text", "PingFang SC", "Microsoft YaHei", sans-serif',
        },
        components: {
          Button: { controlHeight: 40 },
          Input: { controlHeight: 40 },
          Menu: { itemBorderRadius: 9 },
        },
      }}
    >
      <AntApp>
        <AuthProvider>
          <App />
        </AuthProvider>
      </AntApp>
    </ConfigProvider>
  </React.StrictMode>,
)

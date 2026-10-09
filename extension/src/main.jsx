import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import './styles.css';

class PanelBoundary extends React.Component {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    return this.state.failed ? <main><h1>Không mở được panel</h1><p>Tải lại extension rồi mở panel. Meeting đã lưu vẫn ở backend.</p></main> : this.props.children;
  }
}
createRoot(document.getElementById('root')).render(<PanelBoundary><App /></PanelBoundary>);

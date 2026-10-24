import { Component, type ReactNode } from 'react';

export default class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch(error: Error) { console.error('Unhandled UI error', error); }
  render() {
    if (!this.state.failed) return this.props.children;
    return <div className="auth-shell"><div className="auth-card" role="alert">
      <h1>Something went wrong</h1>
      <p>The page hit an unexpected error. Your data is safe on the server. Reload to continue.</p>
      <button onClick={() => window.location.reload()}>Reload</button>
    </div></div>;
  }
}

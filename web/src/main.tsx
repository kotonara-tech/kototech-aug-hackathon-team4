import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { useGoogleAuth } from './auth/useGoogleAuth';
import { googleDrive } from './drive/gateway';
import './styles.css';

/** 本番の口をつなぐだけの層。画面そのものは App にある。 */
function AppRoot() {
  return <App auth={useGoogleAuth()} drive={googleDrive} />;
}

const container = document.getElementById('root');
if (!container) throw new Error('#root が見つかりません');

createRoot(container).render(
  <StrictMode>
    <AppRoot />
  </StrictMode>,
);

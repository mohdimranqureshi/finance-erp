import { Bot, Maximize2, Sparkles, X } from 'lucide-react';
import AiWorkspace from '../AiWorkspace';

export default function AiDock({ open, onOpenChange, onOpenFullPage, hidden, companyId, onChanged }: {
  open: boolean; onOpenChange: (open: boolean) => void; onOpenFullPage: () => void;
  hidden: boolean; companyId: number | ''; onChanged: () => Promise<void>;
}) {
  return <>
    <div className={`aipopup${open && !hidden ? ' open' : ''}`} role="dialog" aria-label="AI assistant">
      <div className="aipopup-head">
        <Bot size={17} /><strong>AI assistant</strong>
        <button className="small secondary" onClick={onOpenFullPage}><Maximize2 size={13} />Full page</button>
        <button className="small secondary" aria-label="Close AI assistant" onClick={() => onOpenChange(false)}>
          <X size={14} />
        </button>
      </div>
      {companyId !== ''
        ? <AiWorkspace compact companyId={Number(companyId)} onChanged={onChanged} onOpenFull={onOpenFullPage} />
        : <p className="empty">Select or create a company to use the AI assistant.</p>}
    </div>

    {!hidden && <button className="aifab" title="AI assistant"
      aria-label={open ? 'Close AI assistant' : 'Open AI assistant'}
      onClick={() => onOpenChange(!open)}>
      {open ? <X size={24} /> : <Sparkles size={24} />}
    </button>}
  </>;
}

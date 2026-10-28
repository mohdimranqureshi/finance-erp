import {useCallback,useEffect,useState} from 'react';
import {api,setBasicAuth,setUnauthorizedHandler} from './api';
import {useToastFeed} from './toast';
import Sidebar from './components/Sidebar';
import TopBar from './components/TopBar';
import CompanyStrip from './components/CompanyStrip';
import AiDock from './components/AiDock';
import SignInView from './views/SignInView';
import OverviewView from './views/OverviewView';
import CompanyView from './views/CompanyView';
import UsersView from './views/UsersView';
import JournalsView from './views/JournalsView';
import ReportsView from './views/ReportsView';
import AiWorkspace from './AiWorkspace';
import type {Account,Company,Journal,Shell,TrialRow,View} from './types';
const IDLE_MS=15*60*1000;
const PAGES:Record<View,{title:string;subtitle:string}>={overview:{title:'Overview',subtitle:'Accounting you can inspect, verify, and trust.'},company:{title:'Company & accounts',subtitle:'Manage your team and chart of accounts.'},users:{title:'Users',subtitle:'Application accounts and administrator access.'},journals:{title:'Journals & ledger',subtitle:'Create, approve and post journals, and check the trial balance.'},reports:{title:'Reports',subtitle:'Run the ledger, profit and loss, and trial balance for any period you choose.'},ai:{title:'AI assistant',subtitle:'Ask questions, draft entries and review the close. AI proposes, people approve.'}};
export default function App(){
 const [email,setEmail]=useState('');
 const [password,setPassword]=useState('');
 const [displayName,setDisplayName]=useState('');
 const [authenticated,setAuthenticated]=useState(false);
 const [isAdmin,setIsAdmin]=useState(false);
 const [inviteToken,setInviteToken]=useState(()=>new URLSearchParams(window.location.hash.slice(1)).get('invite')||'');
 const [companies,setCompanies]=useState<Company[]>([]);
 const [companyId,setCompanyId]=useState<number|''>('');
 const [accounts,setAccounts]=useState<Account[]>([]);
 const [journals,setJournals]=useState<Journal[]>([]);
 const [trial,setTrial]=useState<TrialRow[]>([]);
 const [view,setView]=useState<View>('overview');
 const [aiOpen,setAiOpen]=useState(false);
 const [theme,setTheme]=useState<'light'|'dark'>(()=>(localStorage.getItem('theme') as 'light'|'dark'|null)||(window.matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light'));
 const [message,setMessage]=useState('');
 const [error,setError]=useState('');
 const [busy,setBusy]=useState(false);

 const run=useCallback(async(fn:()=>Promise<void>)=>{
  setBusy(true);setError('');setMessage('');
  try{await fn();return true}
  catch(e){setError(e instanceof Error?e.message:'Unexpected error');return false}
  finally{setBusy(false)}
 },[]);
 const shell:Shell={busy,run,notify:setMessage};

 async function loadCompanies(){
  const rows=await api<Company[]>('/api/v1/companies');
  setCompanies(rows);
  if(!companyId&&rows.length)setCompanyId(rows[0].id);
 }
 const loadData=useCallback(async(id:number)=>{
  const [nextAccounts,nextJournals]=await Promise.all([
   api<Account[]>(`/api/v1/companies/${id}/accounts`),
   api<Journal[]>(`/api/v1/companies/${id}/journals`)]);
  setAccounts(nextAccounts);setJournals(nextJournals);
  const today=new Date().toISOString().slice(0,10);
  setTrial(await api<TrialRow[]>(`/api/v1/companies/${id}/reports/trial-balance?from=${today.slice(0,4)}-01-01&to=${today}`));
 },[]);
 const reload=useCallback(async()=>{if(companyId!=='')await loadData(Number(companyId))},[companyId,loadData]);

 async function signIn(){
  if(!email.trim()||!password)throw Error('Enter your email and password.');
  setBasicAuth(btoa(`${email.trim()}:${password}`));
  await loadCompanies();
  const me=await api<{admin:boolean}>('/api/v1/auth/me');
  setIsAdmin(me.admin);setAuthenticated(true);
  setMessage('Signed in. Company access is enforced by the API.');
 }
 async function acceptInvitation(){
  if(!inviteToken)throw Error('Invitation token is missing. Open the invitation link from the company owner.');
  if(password.length<12)throw Error('Use a password with at least 12 characters.');
  const accepted=await api<{email:string}>('/api/v1/auth/accept-invitation',{method:'POST',body:JSON.stringify({token:inviteToken,password,displayName})});
  setEmail(accepted.email);setInviteToken('');
  window.history.replaceState({},'',window.location.pathname+window.location.search);
  setPassword('');
  setMessage('Invitation accepted. Sign in with the email address invited by the company owner and the password you just created.');
 }
 function signOut(notice=''){
  setBasicAuth('');
  setAuthenticated(false);setIsAdmin(false);setPassword('');
  setCompanies([]);setCompanyId('');setAccounts([]);setJournals([]);setTrial([]);
  setAiOpen(false);setView('overview');setError('');setMessage(notice);
 }
 const createCompany=(name:string)=>run(async()=>{
  if(!name.trim())throw Error('Enter a company name.');
  const created=await api<Company>('/api/v1/companies',{method:'POST',body:JSON.stringify({name,baseCurrency:'INR'})});
  await loadCompanies();setCompanyId(created.id);
  setMessage(`Created company ${created.name}.`);
 });

 useEffect(()=>{document.documentElement.dataset.theme=theme;localStorage.setItem('theme',theme)},[theme]);
 useEffect(()=>{setUnauthorizedHandler(()=>{setBasicAuth('');if(authenticated)signOut('Your session has expired. Please sign in again.')});return()=>setUnauthorizedHandler(null)},[authenticated]);
 useEffect(()=>{if(!authenticated)return;let t=0;const events=['mousedown','keydown','touchstart','scroll'];const reset=()=>{window.clearTimeout(t);t=window.setTimeout(()=>signOut('Signed out after 15 minutes of inactivity.'),IDLE_MS)};events.forEach(e=>window.addEventListener(e,reset,{passive:true}));reset();return()=>{window.clearTimeout(t);events.forEach(e=>window.removeEventListener(e,reset))}},[authenticated]);
 useEffect(()=>{if(companyId!=='')loadData(Number(companyId)).catch(e=>setError(e.message))},[companyId,loadData]);

 const debits=trial.reduce((s,r)=>s+Number(r.debitTotal||0),0),credits=trial.reduce((s,r)=>s+Number(r.creditTotal||0),0);
 useToastFeed(error,message);
 if(!authenticated) return <SignInView inviteToken={inviteToken}
  email={email} setEmail={setEmail} password={password} setPassword={setPassword}
  displayName={displayName} setDisplayName={setDisplayName}
  busy={busy} error={error} message={message}
  onSubmit={()=>run(inviteToken?acceptInvitation:signIn)}/>;

 return <div className="shell">
  <Sidebar view={view} onNavigate={setView} isAdmin={isAdmin} email={email} onSignOut={()=>signOut()}/>

  <main className="main">
   <TopBar title={PAGES[view].title} subtitle={PAGES[view].subtitle} theme={theme} busy={busy}
    onToggleTheme={()=>setTheme(theme==='dark'?'light':'dark')}
    onRefresh={()=>run(async()=>{await loadCompanies();await reload()})}/>

   {view!=='users'&&<CompanyStrip companies={companies} companyId={companyId}
    onSelect={setCompanyId} onCreate={createCompany} busy={busy}/>}

   {view==='overview'&&<OverviewView companyId={companyId} accounts={accounts} journals={journals}
    debits={debits} credits={credits} reload={reload}/>}

   {view==='company'&&<CompanyView companyId={companyId} accounts={accounts} reload={reload} shell={shell}/>}

   {view==='users'&&isAdmin&&<UsersView shell={shell} currentEmail={email}/>}

   {view==='journals'&&<JournalsView companyId={companyId} accounts={accounts} journals={journals}
    trial={trial} debits={debits} credits={credits} reload={reload} shell={shell}/>}

   {view==='reports'&&<ReportsView companyId={companyId} accounts={accounts} shell={shell}/>}

   {view==='ai'&&(companyId!==''
    ? <AiWorkspace companyId={Number(companyId)} onChanged={reload}/>
    : <p className="empty">Select or create a company to use the AI assistant.</p>)}

   <footer>FinoraAI · Approval workflow enabled · AI proposes, people approve</footer>
  </main>

  <AiDock open={aiOpen} onOpenChange={setAiOpen} hidden={view==='ai'} companyId={companyId} onChanged={reload}
   onOpenFullPage={()=>{setView('ai');setAiOpen(false)}}/>
 </div>;
}

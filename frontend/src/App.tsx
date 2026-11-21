import {useCallback,useEffect,useState} from 'react';
import {api,ensureCsrfToken,login,logout,MfaRequiredError,setUnauthorizedHandler} from './api';
import type {SessionUser} from './api';
import {useToastFeed} from './toast';
import Sidebar from './components/Sidebar';
import TopBar from './components/TopBar';
import CompanyStrip from './components/CompanyStrip';
import AiDock from './components/AiDock';
import SignInView from './views/SignInView';
import OverviewView from './views/OverviewView';
import CompanyView from './views/CompanyView';
import UsersView from './views/UsersView';
import ClientsView from './views/ClientsView';
import Gstr2bView from './views/Gstr2bView';
import TallyView from './views/TallyView';
import ReturnsView from './views/ReturnsView';
import OpeningBalancesView from './views/OpeningBalancesView';
import FixedAssetsView from './views/FixedAssetsView';
import SecurityView from './views/SecurityView';
import JournalsView from './views/JournalsView';
import ReportsView from './views/ReportsView';
import AiWorkspace from './AiWorkspace';
import type {Account,Company,Journal,Shell,TrialRow,View} from './types';
const IDLE_MS=15*60*1000;
const PAGES:Record<View,{title:string;subtitle:string}>={overview:{title:'Overview',subtitle:'Accounting you can inspect, verify, and trust.'},clients:{title:'Clients',subtitle:'Every client you work for: close status and the approvals waiting on you.'},gstr2b:{title:'GSTR-2B ITC',subtitle:'Reconcile your purchases with GSTR-2B and see input tax credit at risk.'},tally:{title:'Tally import and export',subtitle:'Bring a TallyPrime day book in as draft journals, or send posted journals back.'},returns:{title:'GSTR-3B and TDS',subtitle:'Review the monthly GST return and record TDS deposits for the quarterly return.'},opening:{title:'Opening balances',subtitle:'Carry your balances forward from the day you start.'},assets:{title:'Fixed assets',subtitle:'Register assets, charge depreciation and record disposals as draft journals.'},company:{title:'Company & accounts',subtitle:'Manage your team and chart of accounts.'},users:{title:'Users',subtitle:'Application accounts and administrator access.'},security:{title:'Security',subtitle:'Two-factor authentication for your account.'},journals:{title:'Journals & ledger',subtitle:'Create, approve and post journals, and check the trial balance.'},reports:{title:'Reports',subtitle:'Run the ledger, profit and loss, and trial balance for any period you choose.'},ai:{title:'AI assistant',subtitle:'Ask questions, draft entries and review the close. AI proposes, people approve.'}};
export default function App(){
 const [email,setEmail]=useState('');
 const [password,setPassword]=useState('');
 const [displayName,setDisplayName]=useState('');
 const [authenticated,setAuthenticated]=useState(false);
 const [isAdmin,setIsAdmin]=useState(false);
 const [mfaEnabled,setMfaEnabled]=useState(false);
 const [mfaStep,setMfaStep]=useState(false);
 const [mfaCode,setMfaCode]=useState('');
 const [useRecovery,setUseRecovery]=useState(false);
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

 function startSession(me:SessionUser){
  setEmail(me.email);setIsAdmin(me.admin);setMfaEnabled(me.mfaEnabled);setAuthenticated(true);
 }
 async function signIn(){
  if(!email.trim()||!password)throw Error('Enter your email and password.');
  const code=mfaCode.trim();
  if(mfaStep&&!code)throw Error(useRecovery?'Enter one of your recovery codes.':'Enter the 6-digit code from your authenticator app.');
  let me:SessionUser;
  try{
   me=await login(email.trim(),password,mfaStep?(useRecovery?{recoveryCode:code}:{totpCode:code}):undefined);
  }catch(e){
   if(e instanceof MfaRequiredError){
    setMfaCode('');
    if(!mfaStep){setMfaStep(true);setMessage(e.message);return}
   }
   throw e;
  }
  setPassword('');setMfaStep(false);setMfaCode('');setUseRecovery(false);
  await loadCompanies();
  startSession(me);
  setMessage('Signed in. Company access is enforced by the API.');
 }
 function cancelMfa(){setMfaStep(false);setMfaCode('');setUseRecovery(false);setPassword('');setError('');setMessage('')}
 async function acceptInvitation(){
  if(!inviteToken)throw Error('Invitation token is missing. Open the invitation link from the company owner.');
  if(password.length<12)throw Error('Use a password with at least 12 characters.');
  const accepted=await api<{email:string}>('/api/v1/auth/accept-invitation',{method:'POST',body:JSON.stringify({token:inviteToken,password,displayName})});
  setEmail(accepted.email);setInviteToken('');
  window.history.replaceState({},'',window.location.pathname+window.location.search);
  setPassword('');
  setMessage('Invitation accepted. Sign in with the email address invited by the company owner and the password you just created.');
 }
 function signOut(notice='',endServerSession=true){
  if(endServerSession)void logout();
  setAuthenticated(false);setIsAdmin(false);setMfaEnabled(false);setPassword('');
  setMfaStep(false);setMfaCode('');setUseRecovery(false);
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
 useEffect(()=>{
  // Resume an existing server session after a page reload; otherwise just make sure a CSRF cookie exists.
  api<SessionUser>('/api/v1/auth/me').then(async me=>{await loadCompanies();startSession(me)}).catch(()=>ensureCsrfToken());
 },[]);
 useEffect(()=>{setUnauthorizedHandler(()=>{if(authenticated)signOut('Your session has expired. Please sign in again.',false)});return()=>setUnauthorizedHandler(null)},[authenticated]);
 useEffect(()=>{if(!authenticated)return;let t=0;const events=['mousedown','keydown','touchstart','scroll'];const reset=()=>{window.clearTimeout(t);t=window.setTimeout(()=>signOut('Signed out after 15 minutes of inactivity.'),IDLE_MS)};events.forEach(e=>window.addEventListener(e,reset,{passive:true}));reset();return()=>{window.clearTimeout(t);events.forEach(e=>window.removeEventListener(e,reset))}},[authenticated]);
 useEffect(()=>{if(companyId!=='')loadData(Number(companyId)).catch(e=>setError(e.message))},[companyId,loadData]);

 const debits=trial.reduce((s,r)=>s+Number(r.debitTotal||0),0),credits=trial.reduce((s,r)=>s+Number(r.creditTotal||0),0);
 useToastFeed(error,message);
 if(!authenticated) return <SignInView inviteToken={inviteToken}
  email={email} setEmail={setEmail} password={password} setPassword={setPassword}
  displayName={displayName} setDisplayName={setDisplayName}
  mfaStep={mfaStep} mfaCode={mfaCode} setMfaCode={setMfaCode}
  useRecovery={useRecovery} setUseRecovery={v=>{setUseRecovery(v);setMfaCode('')}} onCancelMfa={cancelMfa}
  busy={busy} error={error} message={message}
  onSubmit={()=>run(inviteToken?acceptInvitation:signIn)}/>;

 return <div className="shell">
  <Sidebar view={view} onNavigate={setView} isAdmin={isAdmin} email={email} onSignOut={()=>signOut()}/>

  <main className="main">
   <TopBar title={PAGES[view].title} subtitle={PAGES[view].subtitle} theme={theme} busy={busy}
    onToggleTheme={()=>setTheme(theme==='dark'?'light':'dark')}
    onRefresh={()=>run(async()=>{await loadCompanies();await reload()})}/>

   {view!=='users'&&view!=='security'&&view!=='clients'&&<CompanyStrip companies={companies} companyId={companyId}
    onSelect={setCompanyId} onCreate={createCompany} busy={busy}/>}

   {view==='clients'&&<ClientsView shell={shell} onOpenCompany={id=>{setCompanyId(id);setView('journals')}}/>}

   {view==='overview'&&<OverviewView companyId={companyId} accounts={accounts} journals={journals}
    debits={debits} credits={credits} reload={reload}/>}

   {view==='company'&&<CompanyView companyId={companyId} accounts={accounts} reload={reload} shell={shell}/>}

   {view==='users'&&isAdmin&&<UsersView shell={shell} currentEmail={email}/>}

   {view==='security'&&<SecurityView shell={shell} mfaEnabled={mfaEnabled} onChanged={setMfaEnabled}/>}

   {view==='journals'&&<JournalsView companyId={companyId} accounts={accounts} journals={journals}
    trial={trial} debits={debits} credits={credits} reload={reload} shell={shell}/>}

   {view==='reports'&&<ReportsView companyId={companyId} accounts={accounts} shell={shell}/>}

   {view==='gstr2b'&&<Gstr2bView companyId={companyId} shell={shell}/>}

   {view==='tally'&&<TallyView companyId={companyId} shell={shell}/>}

   {view==='returns'&&<ReturnsView companyId={companyId} accounts={accounts} shell={shell}/>}

   {view==='opening'&&<OpeningBalancesView companyId={companyId} accounts={accounts} shell={shell}/>}

   {view==='assets'&&<FixedAssetsView companyId={companyId} accounts={accounts} shell={shell}/>}

   {view==='ai'&&(companyId!==''
    ? <AiWorkspace companyId={Number(companyId)} onChanged={reload}/>
    : <p className="empty">Select or create a company to use the AI assistant.</p>)}

   <footer>FinoraAI · Approval workflow enabled · AI proposes, people approve</footer>
  </main>

  <AiDock open={aiOpen} onOpenChange={setAiOpen} hidden={view==='ai'} companyId={companyId} onChanged={reload}
   onOpenFullPage={()=>{setView('ai');setAiOpen(false)}}/>
 </div>;
}

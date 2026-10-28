export type Company = { id: number; name: string; baseCurrency: string; status: string };
export type Account = { id: number; code: string; name: string; accountType: string };
export type Journal = {
  id: number; journalDate: string; description: string; status: string;
  reversalOfJournalId?: number | null; reversedByJournalId?: number | null;
};
export type TrialRow = {
  accountId: number; code: string; name: string; accountType: string;
  debitTotal: number; creditTotal: number;
};
export type View = 'overview' | 'company' | 'users' | 'journals' | 'reports' | 'ai';

/** Shared plumbing passed to every view: async runner, busy flag and the success-message sink. */
export type Shell = {
  busy: boolean;
  /** Resolves true when the action completed without throwing. */
  run: (fn: () => Promise<void>) => Promise<boolean>;
  notify: (message: string) => void;
};

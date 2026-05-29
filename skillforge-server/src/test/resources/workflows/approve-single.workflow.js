export const meta = {
  name: 'approve-single',
  description: 'agent → humanApprove → agent (Sprint 2 resume IT fixture)',
  phases: [{ title: 'Work', detail: 'one gate' }]
};

phase('Work');
const a = agent('first step', { agentSlug: 'session-annotator' });
const decision = humanApprove({ ask: 'please review the first step' });
const b = agent('second step', { agentSlug: 'session-annotator' });
return ({ a: a, approved: decision.approved, b: b });

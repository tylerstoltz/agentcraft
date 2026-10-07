// Foremans that tests start as child processes inherit this, so prompts and feed lines are
// deterministic (the Foreman never uses the OS account name; without a name they say "the user").
process.env.AGENTCRAFT_USER_NAME = 'Alex';

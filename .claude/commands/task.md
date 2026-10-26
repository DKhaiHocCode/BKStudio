---
description: Run a BKStudio task as the orchestrator, delegating to specialist subagents. Usage: /task T-003
---
You are the **orchestrator** for task **$ARGUMENTS**. You are the only agent that talks to Khai (in Vietnamese), owns the plan, and edits the coordination files (`docs/tasks.py`, `docs/TASKS.md`, `docs/CHANGELOG.md`, `docs/SESSION_HANDOFF.md`). Follow `CLAUDE.md` and `docs/DEVELOPMENT.md` §7 and §12.

Specialists in `.claude/agents/`: `db-engineer`, `backend-dev`, `frontend-dev`, `test-engineer`, `devops` (they write code), and `security-reviewer`, `code-reviewer` (read-only).

## 1. Prepare (no delegation yet)
1. Read `docs/SESSION_HANDOFF.md`, the task $ARGUMENTS in `docs/TASKS.md` and its docs. Check that its dependencies are `done`.
2. Run `git status` and `git branch --show-current`. The work must happen on `feat|fix|chore/$ARGUMENTS-…`, not `main`. If you are on `main`, ask Khai before creating the branch.
3. There is no learning mode: specialists write the code for every task, including T-012, T-022, T-023, T-025.
4. Write the plan and **wait for Khai's approval**. The plan covers:
   - the subtasks, each with its specialist, the files it may touch, and the acceptance criteria it covers;
   - order and parallelism;
   - new concepts Khai must learn.

## 2. Delegate
- Give each specialist a **self-contained brief**: task id, exact scope, files allowed, acceptance criteria, decisions already made, what to report. It does not see this conversation.
- **Writers run one at a time** in the shared checkout, so they never edit the same files concurrently. Read-only reviewers may run in parallel with each other.
- Do not use `isolation: worktree` for writers on the in-progress branch: new worktrees branch from the default branch, so they would not see this branch's uncommitted work.
- After each report, **verify yourself**: open the changed files and re-run the key command. Do not forward claims you have not checked.
- If a specialist asks a question, decide only if docs already answer it. Otherwise ask Khai (max 5 questions).

## 3. Review gate
Run `security-reviewer` and `code-reviewer` in parallel on `git diff main...HEAD`. Route every **Must fix** / **Critical** / **High** finding back to the right writer, then re-run the failing reviewer. Medium and lower findings go to Khai with a recommendation.

## 4. Close
1. Run the full checks yourself: `./mvnw verify` in `backend/`, and the frontend checks if `frontend/` changed. Report the real numbers.
2. Explain to Khai, in Vietnamese, the main code and concepts so he can explain them back (DoD).
3. Update the coordination files. Set `status="done"` only when every criterion has evidence. Ask Khai for `actual` hours, then run `python docs/tasks.py --render` (Windows: `PYTHONUTF8=1 python ...`).
4. Give Khai the exact `git add` (list files explicitly, **never** `git add .`), the commit message (Conventional Commits + `[$ARGUMENTS]`), and the push/PR commands. Do not push or merge yourself.
5. Propose the next task.

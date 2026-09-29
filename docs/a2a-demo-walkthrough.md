# A2A inside the Business Simulation Engine

Use the [combined Carl walkthrough](demo-walkthrough.md) as the main demo.
It now runs **manual comparison → AI exploration of the same draft → scope
approval → simulation results → publication → customer feedback**, using the
mainframe menus throughout.

The only PowerShell commands are in startup, before opening the terminal.
Do not paste API requests, variable expressions or JSON-inspection commands into
a mainframe screen.

## Entry points

- **02 Business → 08 AI-assisted simulation and offer feedback → 1 Explore an existing draft**.
- From a Business draft: **7 AI explore this draft**.

Both enter the same A2A workflow. The second path carries the current draft forward
from the manual comparison.

## What replaces the API commands

| Task | Mainframe action |
| --- | --- |
| Start AI | Select a draft, discovery population and fresh validation population; enter intent and budget |
| Read and approve evidence | AI home → **3 Review local policy documents** |
| Inspect proposed ranges | Workflow → **1 Inspect exact scope / ranges / evidence IDs** |
| Approve simulation execution | Workflow → **3 Approve reviewed scope and start simulations**, then **A** |
| Read winners, analysis and reflection | Workflow → **2 Inspect analysis, limitations and workflow** |
| Inspect A2A step roles/task IDs | In the same report, page through **steps** |
| Browse trials and exact configurations | Workflow → **5 All simulation results and trial status** |
| Publish a winner | Workflow → **6 Preview a tested winner for publication**; inspect preview, then **P** |
| Read observed performance and saved estimate | AI home → **4 Published offer performance** → select publication → **1** |
| Request a researched explanation | Publication → **2 Ask why**, with an explicit new budget |
| Explore corrective edits | Publication → **3 Explore edits**, using fresh populations |

Use **F7/F8** to page through displayed reports and **F3** to return.
On the workflow screen, a blank choice and Enter refreshes progress.

The [combined walkthrough](demo-walkthrough.md#business-case-manual-comparison-then-ai-on-the-same-draft)
contains the exact Carl inputs, policy approval, separate population seeds,
$10 cap, terminal budget fields, approval steps and expected results.
It also explains which fields stay fixed from the manual draft.

The terminal exposes the functional analyst workflow. Full raw agent-artifact
downloads and complete token/cost accounting are optional engineering diagnostics,
documented in [developer/API verification](a2a-api-verification.md). That reference
is not a second required analyst demo.

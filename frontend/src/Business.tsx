import { useState } from "react";
import { key } from "./api";
import { BusinessBriefing } from "./BusinessBriefing";
import { Investigations } from "./Investigations";
import { Dashboard } from "./Dashboard";
import { Experiments } from "./Experiments";
import { AiWorkspace, Policies } from "./Ai";
/** Business navigation opens a durable Network 2 briefing and preserves direct access to performance, investigation and simulation screens. */
export function BusinessWorkspace({
  view,
  setView,
}: {
  view: string;
  setView: (s: string) => void;
}) {
  const [draftId, setDraftId] = useState(""),
    [publicationId, setPublicationId] = useState(""),
    [runId, setRunId] = useState("");
  const [briefingRequestId, setBriefingRequestId] = useState(key);
  if (view === "briefing") return <BusinessBriefing key={briefingRequestId} requestId={briefingRequestId} onRefresh={() => setBriefingRequestId(key())} />;
  if (view === "performance")
    return (
      <Dashboard
        onInvestigate={(id) => {
          setPublicationId(id);
          setDraftId("");
          setView("investigations");
        }}
        onRun={(id) => {
          setRunId(id);
          setView("experiments");
        }}
        onNew={() => setView("experiments")}
      />
    );
  if (view === "investigations") return <Investigations publicationId={publicationId}/>;
  if (view === "experiments")
    return (
      <Experiments
        initialRun={runId}
        onAi={(id) => {
          setDraftId(id);
          setPublicationId("");
          setView("ai");
        }}
      />
    );
  if (view === "policies") return <Policies />;
  return (
    <AiWorkspace
      draftId={draftId}
      publicationId={publicationId}
      onPolicies={() => setView("policies")}
      onPublished={() => setView("performance")}
    />
  );
}

import { useState } from "react";
import { Investigations } from "./Investigations";
import { Dashboard } from "./Dashboard";
import { Experiments } from "./Experiments";
import { AiWorkspace, Policies } from "./Ai";
/** Business navigation separates performance reads, compound investigations and simulation experiments. */
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

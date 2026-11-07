import React from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { Presenter } from "./Presenter";
import "./styles.css";
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
    <Presenter />
  </React.StrictMode>,
);

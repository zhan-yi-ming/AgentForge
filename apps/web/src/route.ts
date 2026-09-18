import { useCallback, useEffect, useState } from "react";

export type AppRoute = { page: "home" | "chat" | "wiki"; conversationId?: string };

export function parseRoute(pathname: string): AppRoute {
  if (pathname === "/chat") return { page: "chat" };
  const match = /^\/chat\/([^/]+)$/.exec(pathname);
  if (match) {
    try { return { page: "chat", conversationId: decodeURIComponent(match[1]) }; }
    catch { return { page: "home" }; }
  }
  if (pathname === "/wiki") return { page: "wiki" };
  return { page: "home" };
}

export function useAppRoute() {
  const [route, setRoute] = useState(() => parseRoute(window.location.pathname));
  useEffect(() => {
    const onPopState = () => setRoute(parseRoute(window.location.pathname));
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, []);
  const navigate = useCallback((path: string, replace = false) => {
    if (window.location.pathname !== path) window.history[replace ? "replaceState" : "pushState"]({}, "", path);
    setRoute(parseRoute(path));
  }, []);
  return { route, navigate };
}

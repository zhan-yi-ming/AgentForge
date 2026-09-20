import { useMemo, useRef, useState, type PointerEvent } from "react";
import type { WikiPage } from "../api";
import "./WikiGraphPage.css";

type GraphLink = { sourceId: string; targetId: string; labels: string[] };
type PositionedPage = { page: WikiPage; x: number; y: number; degree: number };
type Props = { projectId: string; pages: WikiPage[]; error?: string; onBack: () => void; onOpenPage: (page: WikiPage) => void };

function linksFromPage(page: WikiPage, byId: Map<string, WikiPage>, byTitle: Map<string, WikiPage>): GraphLink[] {
  const found = new Map<string, GraphLink>();
  let fenced = false;
  for (const line of page.content.split(/\r?\n/)) {
    if (/^\s*(`{3,}|~{3,})/.test(line)) { fenced = !fenced; continue; }
    if (fenced) continue;
    const readable = line.replace(/`[^`]*`/g, "");
    const add = (target: WikiPage | undefined, label: string) => {
      if (!target || target.id === page.id) return;
      const current = found.get(target.id) ?? { sourceId: page.id, targetId: target.id, labels: [] };
      if (!current.labels.includes(label)) current.labels.push(label);
      found.set(target.id, current);
    };
    for (const match of readable.matchAll(/\[\[([^\]|\n]+)(?:\|([^\]\n]+))?\]\]/g)) {
      const name = match[1].trim();
      add(byId.get(name) ?? byTitle.get(name.toLocaleLowerCase()), (match[2] ?? name).trim());
    }
    for (const match of readable.matchAll(/\[([^\]\n]+)\]\((?:wiki:|\/wiki\/)([^)\s#]+)(?:#[^)]*)?\)/g)) {
      let id = match[2];
      try { id = decodeURIComponent(id); } catch { continue; }
      add(byId.get(id), match[1].trim());
    }
  }
  return [...found.values()];
}

function graphFor(projectId: string, allPages: WikiPage[]) {
  const pages = allPages.filter((page) => page.projectId === projectId);
  const byId = new Map(pages.map((page) => [page.id, page]));
  const byTitle = new Map(pages.map((page) => [page.title.trim().toLocaleLowerCase(), page]));
  const links = pages.flatMap((page) => linksFromPage(page, byId, byTitle));
  const degrees = new Map(pages.map((page) => [page.id, 0]));
  for (const link of links) {
    degrees.set(link.sourceId, (degrees.get(link.sourceId) ?? 0) + 1);
    degrees.set(link.targetId, (degrees.get(link.targetId) ?? 0) + 1);
  }
  const sorted = [...pages].sort((a, b) => (degrees.get(b.id) ?? 0) - (degrees.get(a.id) ?? 0) || a.title.localeCompare(b.title));
  const positions = new Map<string, PositionedPage>();
  sorted.forEach((page, index) => {
    const ring = Math.floor(index / 8);
    const ringStart = ring * 8;
    const ringSize = Math.min(8, sorted.length - ringStart);
    const angle = ((index - ringStart) / ringSize) * Math.PI * 2 - Math.PI / 2;
    const radiusX = Math.min(425, 315 + ring * 60);
    const radiusY = Math.min(280, 220 + ring * 35);
    positions.set(page.id, { page, x: 500 + Math.cos(angle) * radiusX, y: 320 + Math.sin(angle) * radiusY, degree: degrees.get(page.id) ?? 0 });
  });
  return { pages, links, positions };
}

export default function WikiGraphPage({ projectId, pages: allPages, error, onBack, onOpenPage }: Props) {
  const graph = useMemo(() => graphFor(projectId, allPages), [projectId, allPages]);
  const [query, setQuery] = useState("");
  const [selectedId, setSelectedId] = useState<string>();
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const drag = useRef<{ x: number; y: number; panX: number; panY: number } | undefined>(undefined);
  const selected = graph.pages.find((page) => page.id === selectedId) ?? graph.pages[0];
  const visible = graph.pages.filter((page) => page.title.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const visibleIds = new Set(visible.map((page) => page.id));
  const visibleLinks = graph.links.filter((link) => visibleIds.has(link.sourceId) && visibleIds.has(link.targetId));
  const related = selected ? graph.links.filter((link) => link.sourceId === selected.id || link.targetId === selected.id) : [];
  const onPointerDown = (event: PointerEvent<HTMLDivElement>) => {
    if ((event.target as Element).closest(".wiki-graph-node")) return;
    drag.current = { x: event.clientX, y: event.clientY, panX: pan.x, panY: pan.y };
    event.currentTarget.setPointerCapture?.(event.pointerId);
  };
  const onPointerMove = (event: PointerEvent<HTMLDivElement>) => {
    if (!drag.current) return;
    setPan({ x: drag.current.panX + event.clientX - drag.current.x, y: drag.current.panY + event.clientY - drag.current.y });
  };
  const reset = () => { setZoom(1); setPan({ x: 0, y: 0 }); setQuery(""); };
  return <section className="wiki-graph-page" aria-label="Wiki 知识图谱">
    {error && <p role="alert" className="wiki-graph-error">{error}</p>}
    <header className="wiki-graph-header">
      <div><button className="wiki-graph-back" onClick={onBack}>← 返回 Wiki</button><p className="wiki-graph-kicker">AGENTFORGE / KNOWLEDGE ATLAS</p><h1>知识图谱</h1><p>探索项目知识的脉络，每个 Wiki 都是一颗独特的星球。</p></div>
      <div className="wiki-graph-stats"><div><strong>{graph.pages.length}</strong><span>{graph.pages.length} 个页面</span></div><div><strong>{graph.links.length}</strong><span>{graph.links.length} 条连接</span></div></div>
    </header>
    <div className="wiki-graph-toolbar"><label>⌕ <input aria-label="搜索 Wiki 页面" placeholder="搜索页面名称…" value={query} onChange={(event) => setQuery(event.target.value)} /></label><div><button aria-label="缩小图谱" onClick={() => setZoom((value) => Math.max(.65, +(value - .2).toFixed(2)))}>−</button><span>{Math.round(zoom * 100)}%</span><button aria-label="放大图谱" onClick={() => setZoom((value) => Math.min(2, +(value + .2).toFixed(2)))}>＋</button><button onClick={reset}>重置视图</button></div></div>
    <div className="wiki-graph-body">
      <div className="wiki-graph-viewport" onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={() => { drag.current = undefined; }} onPointerCancel={() => { drag.current = undefined; }}>
        <div className="wiki-graph-stage" style={{ transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})` }}>
          <svg viewBox="0 0 1000 640" preserveAspectRatio="none" aria-hidden="true"><defs><linearGradient id="wiki-graph-edge"><stop stopColor="#65e0de" /><stop offset="1" stopColor="#9c7af9" /></linearGradient></defs><ellipse className="wiki-graph-orbit" cx="500" cy="320" rx="285" ry="205" /><ellipse className="wiki-graph-orbit" cx="500" cy="320" rx="425" ry="280" />{visibleLinks.map((link) => { const source = graph.positions.get(link.sourceId)!; const target = graph.positions.get(link.targetId)!; return <line key={`${link.sourceId}-${link.targetId}`} x1={source.x} y1={source.y} x2={target.x} y2={target.y} className={selectedId && link.sourceId !== selectedId && link.targetId !== selectedId ? "muted" : ""} />; })}</svg>
          <div className="wiki-graph-hub" aria-hidden="true"><span>AgentForge</span></div>
          {visible.map((page) => { const point = graph.positions.get(page.id)!; return <button key={page.id} type="button" className={`wiki-graph-node wiki-graph-node-${graph.pages.indexOf(page) % 7}${page.id === selected?.id ? " selected" : ""}${point.degree ? " connected" : ""}`} style={{ left: `${point.x / 10}%`, top: `${point.y / 6.4}%` }} aria-label={`查看 ${page.title}`} onClick={() => setSelectedId(page.id)}><span className="wiki-graph-node-core" aria-hidden="true" /><strong>{page.title}</strong><small>{point.degree ? `${point.degree} 条关联` : "独立页面"}</small></button>; })}
        </div>
        {!graph.pages.length && !error && <div className="wiki-graph-empty">当前项目还没有 Wiki 页面。先在 Wiki 工作台创建页面。</div>}
        {graph.pages.length > 0 && !visible.length && <div className="wiki-graph-empty">没有找到匹配的页面。</div>}
        <div className="wiki-graph-hint">✦ 拖动画布可移动，滚轮缩放，点击星球查看详情</div>
      </div>
      <aside className="wiki-graph-inspector"><span className="wiki-graph-kicker">WIKI / 页面详情</span>{selected ? <><div className="wiki-graph-inspector-icon">✦</div><h2>{selected.title}</h2><p>{related.length ? `${related.length} 条可追溯的页面连接` : "这个页面还没有可解析的链接关系"}</p><button className="wiki-graph-open" onClick={() => onOpenPage(selected)}>打开 Wiki 页面 ↗</button><h3>链接关系</h3><div className="wiki-graph-relations">{related.map((link) => { const other = graph.pages.find((page) => page.id === (link.sourceId === selected.id ? link.targetId : link.sourceId))!; return <button key={`${link.sourceId}-${link.targetId}`} onClick={() => setSelectedId(other.id)}><small>{link.sourceId === selected.id ? "指向" : "来自"}</small><strong>{other.title}</strong><span>{link.labels.join(" · ")}</span></button>; })}{!related.length && <p>在正文中使用 [[页面标题]] 建立可见连接。</p>}</div><h3>页面列表</h3><div className="wiki-graph-page-list">{graph.pages.map((page) => <button key={page.id} type="button" onClick={() => setSelectedId(page.id)} className={page.id === selected.id ? "active" : ""}><span>▤</span>{page.title}<span>›</span></button>)}</div></> : <><div className="wiki-graph-inspector-icon">◎</div><h2>选择一个页面</h2><p>查看它与其他 Wiki 页面之间的显式链接，并跳转到原始内容。</p><div className="wiki-graph-inspector-note">所有关系均来自当前项目的 Wiki 正文。</div></>}</aside>
    </div>
  </section>;
}

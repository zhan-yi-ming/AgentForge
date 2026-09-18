import type { Task } from "../api";

export default function TaskView({ tasks }: { tasks: Task[] }) {
  return <div className="workspace-view tasks-view"><div className="panel-heading"><div><span className="eyebrow">EXECUTION</span><h2>执行任务</h2></div><span className="count">{tasks.length}</span></div><p className="task-explanation">这里只展示明确创建并经你确认的任务；普通提问和 Wiki 保存不会新增任务。</p><div className="task-list">{tasks.map((task) => <article key={task.id}><span className={`priority ${task.priority.toLowerCase()}`}>{task.priority}</span><h3>{task.title}</h3><p>{task.description || "暂无描述"}</p><footer><span>{task.status.replace("_", " ")}</span><span>v{task.version}</span></footer></article>)}{!tasks.length && <p className="empty-state">暂无任务，可让 Agent 提出一个。</p>}</div></div>;
}

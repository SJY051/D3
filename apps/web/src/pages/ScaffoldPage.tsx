interface ScaffoldPageProps {
  requirement: string;
  sections: readonly string[];
  title: string;
  wireframe: string;
}

export function ScaffoldPage({ requirement, sections, title, wireframe }: ScaffoldPageProps) {
  return (
    <article className="scaffold-page" data-requirement={requirement} data-wireframe={wireframe}>
      <header>
        <p>{wireframe} · {requirement}</p>
        <h1>{title}</h1>
        <p>This route preserves the approved information hierarchy only. Product behavior is not implemented.</p>
      </header>
      <div className="scaffold-grid">
        {sections.map((section) => (
          <section key={section} aria-label={section}>
            <h2>{section}</h2>
            <p>Reserved for implementation after wireframe review.</p>
          </section>
        ))}
      </div>
    </article>
  );
}

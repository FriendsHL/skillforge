interface FilterItemProps {
  label: string;
  count: number;
  active: boolean;
  onClick: () => void;
}

export function FilterItem({ label, count, active, onClick }: FilterItemProps) {
  return (
    <button className={`filter-item ${active ? 'on' : ''}`} onClick={onClick}>
      <span>{label}</span>
      <span className="filter-item-count">{count}</span>
    </button>
  );
}

// 进度值统一钳制（1.0.4 F）：NaN/null/undefined→0，<0→0，>100→100。所有进度条/环/百分比显示都应过一遍。
export function clampPct(v: number | null | undefined): number {
  const n = typeof v === 'number' ? v : Number(v)
  if (!Number.isFinite(n)) return 0
  return Math.min(100, Math.max(0, n))
}

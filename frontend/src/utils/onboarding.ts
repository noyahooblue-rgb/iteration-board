// 新手引导已看标记（修订三五.1）：按用户+小组存 localStorage。
export function onboardingKey(userId: number, teamId: number) { return `ib-onboard-${userId}-${teamId}` }
export function onboardingSeen(userId: number, teamId: number) { try { return !!localStorage.getItem(onboardingKey(userId, teamId)) } catch { return true } }
export function markOnboardingSeen(userId: number, teamId: number) { try { localStorage.setItem(onboardingKey(userId, teamId), '1') } catch {} }

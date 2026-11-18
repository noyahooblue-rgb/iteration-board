package com.iterationboard.iteration;

import com.iterationboard.common.ApiException;
import com.iterationboard.config.Dtos;
import com.iterationboard.domain.*;
import com.iterationboard.domain.Enums.*;
import com.iterationboard.item.ItemService;
import com.iterationboard.repo.*;
import com.iterationboard.team.TeamService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* 迭代规划（修订一第 6 节）：按任务分组的故事与独立事项、每人负载（共同负责按人数均分故事点）、
* 成员容量（可用天数 = 迭代工作日扣本人非 NORMAL 状态覆盖天数、已认领故事点）。
*/
@Service
public class PlanningService {
private final IterationRepo iterationRepo;
private final ItemRepo itemRepo;
private final ItemOwnerRepo ownerRepo;
private final TeamMemberRepo memberRepo;
private final TeamService teamService;
private final ItemService itemService;

public PlanningService(IterationRepo iterationRepo, ItemRepo itemRepo, ItemOwnerRepo ownerRepo,
TeamMemberRepo memberRepo, TeamService teamService, ItemService itemService) {
this.iterationRepo = iterationRepo; this.itemRepo = itemRepo; this.ownerRepo = ownerRepo;
this.memberRepo = memberRepo; this.teamService = teamService; this.itemService = itemService;
}

@Transactional(readOnly = true)
public Dtos.PlanningDto planning(Long teamId, Long iterationId, AppUser user) {
teamService.requireMember(teamId, user);
Iteration iteration = iterationRepo.findById(iterationId)
.filter(i -> i.getTeam().getId().equals(teamId))
.orElseThrow(() -> ApiException.notFound("迭代不存在"));
List<Item> items = itemRepo.findByTeamId(teamId).stream()
.filter(i -> i.getDeletedAt() == null)
.filter(i -> i.getType()!= ItemType.TASK)
.filter(i -> i.getIteration()!= null && i.getIteration().getId().equals(iterationId))
.sorted(java.util.Comparator.comparingLong(Item::getSeq))
.toList();

// 分组：故事按父任务，独立事项一组（task=null）
Map<Long, List<Item>> byTask = new LinkedHashMap<>();
List<Item> standalone = new ArrayList<>();
for (Item i: items) {
if (i.getParent()!= null) byTask.computeIfAbsent(i.getParent().getId(), k -> new ArrayList<>()).add(i);
else standalone.add(i);
}
List<Dtos.PlanningGroupDto> groups = new ArrayList<>();
for (var e: byTask.entrySet()) {
Item task = itemRepo.findById(e.getKey()).orElse(null);
groups.add(new Dtos.PlanningGroupDto(task == null? null: itemService.toDto(task, user),
e.getValue().stream().map(i -> itemService.toDto(i, user)).toList()));
}
if (!standalone.isEmpty()) {
groups.add(new Dtos.PlanningGroupDto(null, standalone.stream().map(i -> itemService.toDto(i, user)).toList()));
}

// 负载：未完成事项的估算按共同负责人人数均分
Map<Long, BigDecimal> points = new LinkedHashMap<>();
Map<Long, Integer> counts = new LinkedHashMap<>();
for (Item i: items) {
if (i.getStatus() == ItemStatus.DONE || i.getStatus() == ItemStatus.CANCELLED) continue;
List<ItemOwner> owners = ownerRepo.findByItemId(i.getId());
if (owners.isEmpty() || i.getEstimate() == null) {
for (ItemOwner o: owners) counts.merge(o.getUser().getId(), 1, Integer::sum);
continue;
}
BigDecimal share = i.getEstimate().divide(BigDecimal.valueOf(owners.size()), 4, RoundingMode.HALF_UP);
for (ItemOwner o: owners) {
points.merge(o.getUser().getId(), share, BigDecimal::add);
counts.merge(o.getUser().getId(), 1, Integer::sum);
}
}
List<Dtos.PlanningLoadDto> loads = points.keySet().stream()
.map(uid -> new Dtos.PlanningLoadDto(uid, points.get(uid), counts.getOrDefault(uid, 0)))
.toList();

// 容量：迭代工作日（小组设置口径：周一至周五 ± 特殊日）扣本人请假/出差/外借覆盖天数；
// 剩余容量 = 剩余可用工作日 × 容量系数（验收批复待问 3，默认 1），超容量只提示不拦截。
Team team = iteration.getTeam();
com.iterationboard.team.TeamSettings settings = com.iterationboard.team.TeamSettings.from(team);
List<Dtos.PlanningCapacityDto> capacities = new ArrayList<>();
LocalDate today = LocalDate.now();
for (TeamMember m: memberRepo.findByTeamId(teamId)) {
int available = 0;
int remaining = 0;
if (iteration.getStartDate()!= null && iteration.getEndDate()!= null) {
for (LocalDate d = iteration.getStartDate();!d.isAfter(iteration.getEndDate()); d = d.plusDays(1)) {
if (!com.iterationboard.common.Workdays.isWorkday(d, settings)) continue;
if (m.isAwayOn(d)) continue;
available++;
if (!d.isBefore(today)) remaining++;
}
}
Long uid = m.getUser().getId();
BigDecimal claimed = points.getOrDefault(uid, BigDecimal.ZERO);
BigDecimal capacityPoints = settings.capacityPointsPerWorkday.multiply(BigDecimal.valueOf(remaining));
capacities.add(new Dtos.PlanningCapacityDto(uid, available, claimed,
remaining, capacityPoints, claimed.compareTo(capacityPoints) > 0));
}
return new Dtos.PlanningDto(Dtos.IterationDto.of(iteration), groups, loads, capacities);
}
}

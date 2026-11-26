"""Graph engine v3.1 - production mesh-audit engine (replaces v1).

Two-flag taxonomy (user decision): the engine emits exactly two statuses -
VERIFIED and UNVERIFIED. Every non-verified outcome (isolated desk,
below-threshold corroboration, unanchored cluster, witnessed non-submitter,
oversubscribed session) collapses to UNVERIFIED so the teacher manually
checks it. There is no "outside classroom" label anywhere: the engine never
claims to know WHERE an unverified student was, only that the mesh could
not verify them. verification_method carries the cause for the teacher's
context; affinity_score is binary (1.00 verified, 0.00 unverified).

Locked rule set:
1. FULL LISTS: report ALL visible peers, capped at 64 stored entries.
2. DIRECT threshold: T(1)=1, T(2)=2, T(3)=2, T(N>3)=min(10, ceil(N/2))
   (halves round up, capped at 10). Safe at T(1)=1 because a single
   mutual must be triangularly corroborated (rule 5) - except in a
   two-submitter session (rule 8).
3. BRIDGE: need = max(1, T(N) - |M|). The bridge node must itself be
   direct-verified; bridges cannot cascade.
4. FEDERATION FLOOR: two verified components join the same room only
   when >= max(4, ceil(0.3 * |smaller|)) DISTINCT members of each
   component claim at least that many DISTINCT verified members of the
   other ("at least 4 people from group A must hear at least 4 different
   people from group B and vice versa", preventing a single boundary
   phone from merging a whole outside cluster).
5. TRIANGLE CORROBORATION: a mutual edge counts toward verification only
   when both endpoints share a common mutual neighbor; demoted edges
   remain as claim material (witness context) only.
6. ANCHORING (user decision, replaces ratio/face floors): the room
   containing the LARGEST verified component is the main class, always.
   No enrollment-ratio check - submissions are auth-bound to the session
   roster, so every submitter is an enrolled member of that class.
   No face gate - face proves identity, not location (a corridor gang
   face-verifies fine).
7. OVERSUBSCRIPTION: when submission count exceeds enrollment, keep the
   verified classroom intact and hold only the weakest surplus (or
   already-unverified rows) for teacher check instead of wiping all
   honest students.
8. TWO-SUBMITTER SESSION (user decision: the two-phone honest test):
   with exactly two submitters, T(2)=2 is unreachable (no third member
   exists to corroborate a triangle), so the single mutual edge A<->B is
   accepted as sufficient evidence. Fires only when the WHOLE session is
   two submitters; a spoofed pair inside a larger session is unaffected.
   total_enrolled <= 0 is treated as unknown enrollment (oversubscription
   check skipped, like v1).

Ingest hardening: reported lists are deduplicated (order-preserving) and
self-references dropped before any counting, so a client echoing its own
advert or double-listing a peer cannot inflate mutual counts or
thresholds. A client that double-lists a peer gains nothing; one that
echoes its own advert gains no phantom self-mutual.

Not implemented (user standing rejections): rule-4 mutual-edge cap, per-B
sponsorship cap, enrollment-ratio anchoring, face-gated anchoring.
"""

from collections import deque
from math import ceil

FULL_LIST_CAP = 64         # payload sanity cap on stored list length
DIRECT_THRESHOLD_CAP = 10  # cap required mutual triangles on large lists
SUBMIT_LIST_MIN = 0        # optional gate: submitters listing fewer peers
                           # than this are held for review. 0 = off.
FED_ABS_MIN = 4            # user: at least 4 claimers from each side
FED_FRAC = 0.30            # ...or 30% of the smaller component, whichever
                           # is larger

VERIFIED = "VERIFIED"
UNVERIFIED = "UNVERIFIED"


def _threshold(n: int) -> int:
    # T(1)=1, T(2)=2, T(3)=2, T(N>3)=min(DIRECT_THRESHOLD_CAP, ceil(N/2)).
    if n <= 1:
        return 1
    if n == 2 or n == 3:
        return 2
    return min(DIRECT_THRESHOLD_CAP, (n + 1) // 2)


def _sanitize(raw_lists: dict[str, list[str]]) -> dict[str, list[str]]:
    """Deduplicate each reported list (order-preserving) and drop
    self-references before any counting. Without this, a client that
    double-lists a peer inflates its own mutual count past the threshold,
    and one that echoes its own advert gains a phantom self-mutual that
    trivially satisfies triangle corroboration."""
    out: dict[str, list[str]] = {}
    for u, claimed in raw_lists.items():
        seen: dict[str, None] = {}
        for peer in claimed:
            if peer != u:
                seen[peer] = None
        out[u] = list(seen)[:FULL_LIST_CAP]
    return out


def audit_classroom_mesh(
    reports: dict[str, list[str]],
    *,
    total_enrolled: int | None = None,
    # Accepted for call-site parity; v3.1 deliberately does NOT gate
    # anchoring on face evidence (user decision: face proves identity,
    # not location).
    face_verified_keys: set[str] | None = None,
) -> list[dict]:
    lists = _sanitize(reports)
    sets = {u: set(l) for u, l in lists.items()}
    rank_of = {u: {v: idx for idx, v in enumerate(l)} for u, l in lists.items()}

    # Raw mutual graph preserving strongest-first order from lists[u].
    mut = {u: [v for v in lists[u] if v in lists and u in sets[v]] for u in lists}
    mutset = {u: set(peers) for u, peers in mut.items()}

    # Triangle corroboration: evaluate each undirected mutual pair once using
    # non-allocating isdisjoint(); preserve closest-first order in strong[u].
    corroborated_pairs: set[tuple[str, str]] = set()
    for u, peers in mut.items():
        ms_u = mutset[u]
        for v in peers:
            if u < v and not ms_u.isdisjoint(mutset[v]):
                corroborated_pairs.add((u, v))
    strong = {
        u: [v for v in mut[u] if (u, v) in corroborated_pairs or (v, u) in corroborated_pairs]
        for u in lists
    }
    m = {u: len(strong[u]) for u in lists}
    t_of = {u: _threshold(len(lists[u])) for u in lists}

    held = {u for u in lists if len(lists[u]) < SUBMIT_LIST_MIN}
    direct: set[str] = set()
    for u in lists:
        if u in held:
            continue
        if m[u] >= t_of[u]:
            direct.add(u)
        elif len(lists[u]) >= 6 and m[u] >= 3:
            # Lists are ordered strongest RSSI first: if u's closest prefix already
            # clears its proportional threshold, distant 1-way whispers in the tail
            # must not disqualify u from direct verification.
            core_k = max(4, len(lists[u]) // 2)
            strong_in_core = sum(1 for v in strong[u] if rank_of[u][v] < core_k)
            if strong_in_core >= _threshold(core_k):
                direct.add(u)

    # Bridge verification over strong edges, preferring strongest-signal contributors.
    bridge_verified: set[str] = set()
    bridge_info: dict[str, tuple[str, list[str]]] = {}
    for u in lists:
        if u in direct:
            continue
        mu = strong[u]
        if not mu:
            continue
        mu_set = set(mu)
        need = max(1, t_of[u] - len(mu))
        witness_rank: dict[str, int] = {}
        best_bridge: str | None = None
        best_score: tuple[int, int] | None = None
        for b in mu:
            if b not in direct:
                continue  # B must be direct-verified; bridges cannot cascade
            contrib = 0
            for c in strong[b]:
                if c != u and c not in mu_set:
                    contrib += 1
                    r = rank_of[b][c]
                    prev = witness_rank.get(c)
                    if prev is None or r < prev:
                        witness_rank[c] = r
            if contrib > 0:
                score = (-contrib, rank_of[u][b])
                if best_score is None or score < best_score:
                    best_score = score
                    best_bridge = b
        if best_bridge is not None and len(witness_rank) >= need:
            bridge_verified.add(u)
            ordered_cs = sorted(witness_rank, key=lambda c: (witness_rank[c], c))
            bridge_info[u] = (best_bridge, ordered_cs[:need])

    verified = direct | bridge_verified

    # Components over verified nodes (strong edges only).
    adjacency = {u: [v for v in strong[u] if v in verified] for u in verified}
    comps = _components(adjacency)
    comp_of = {u: i for i, comp in enumerate(comps) for u in comp}

    # Federation: union components into rooms when enough DISTINCT claimers
    # exist in both directions (user floor: max(4, 30% of the smaller
    # room)). Evaluated at ROOM level with LIVE sizes and claims aggregated
    # across every original component in each room, repeated to fixpoint:
    # a merge changes room sizes, which can raise (or satisfy) later
    # floors. Using the stale pre-union component sizes let a small stale
    # component collapse the floor to 4 and chain-merge large rooms below
    # the intended 30%-of-smaller-side rule.
    n = len(comps)
    parent = list(range(n))

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    # Live room size, indexed by DSU root.
    size = [len(comp) for comp in comps]

    # Sparse directed cross-room claims: cross_out[ra][rb] = (senders_in_ra, targets_in_rb).
    # Maintained incrementally across DSU merges in O(E log n) instead of an n x n matrix.
    cross_out: dict[int, dict[int, tuple[set[str], set[str]]]] = {}
    in_nbrs: dict[int, set[int]] = {}
    for u in verified:
        i = comp_of[u]
        for v in lists[u]:
            if v in verified:
                j = comp_of[v]
                if j != i:
                    entry = cross_out.setdefault(i, {}).get(j)
                    if entry is None:
                        entry = (set(), set())
                        cross_out[i][j] = entry
                        in_nbrs.setdefault(j, set()).add(i)
                    entry[0].add(u)
                    entry[1].add(v)

    active_roots = set(range(n))
    merged = True
    while merged:
        merged = False
        did_merge = False
        # At most ONE merge per pass in sorted root order: a union changes room
        # sizes and aggregates cross claims before evaluating remaining floors.
        for ra in sorted(active_roots):
            if did_merge:
                break
            out_a = cross_out.get(ra)
            if not out_a:
                continue
            for rb in sorted(out_a):
                if rb <= ra or rb not in active_roots:
                    continue
                out_b = cross_out.get(rb)
                if not out_b or ra not in out_b:
                    continue
                req = max(
                    FED_ABS_MIN,
                    ceil(FED_FRAC * min(size[ra], size[rb])),
                )
                s_ab, t_ab = out_a[rb]
                s_ba, t_ba = out_b[ra]
                if (
                    len(s_ab) >= req
                    and len(t_ab) >= req
                    and len(s_ba) >= req
                    and len(t_ba) >= req
                ):
                    # Attach the smaller room's root under the larger and fold sparse claims.
                    small, big = (ra, rb) if size[ra] <= size[rb] else (rb, ra)
                    parent[small] = big
                    size[big] += size[small]
                    active_roots.discard(small)

                    cross_out.get(small, {}).pop(big, None)
                    cross_out.get(big, {}).pop(small, None)
                    in_nbrs.get(small, set()).discard(big)
                    in_nbrs.get(big, set()).discard(small)

                    for k, (sk, tk) in cross_out.pop(small, {}).items():
                        in_nbrs.get(k, set()).discard(small)
                        in_nbrs.setdefault(k, set()).add(big)
                        dest = cross_out.setdefault(big, {}).get(k)
                        if dest is None:
                            cross_out[big][k] = (sk, tk)
                        else:
                            dest[0].update(sk)
                            dest[1].update(tk)

                    for k in in_nbrs.pop(small, ()):
                        sk, tk = cross_out[k].pop(small)
                        in_nbrs.setdefault(big, set()).add(k)
                        dest = cross_out[k].get(big)
                        if dest is None:
                            cross_out[k][big] = (sk, tk)
                        else:
                            dest[0].update(sk)
                            dest[1].update(tk)

                    merged = True
                    did_merge = True
                    break

    rooms: dict[int, set[str]] = {}
    room_min_comp: dict[int, int] = {}
    for i in range(n):
        r = find(i)
        if r not in room_min_comp:
            room_min_comp[r] = i
        rooms.setdefault(r, set()).update(comps[i])

    # Rule 8: two-submitter session. The mutual edge must be raw (both
    # directions present); triangle corroboration is unreachable here by
    # construction, which is why the rule exists.
    pair_ids = sorted(lists)
    pair_direct = (
        len(pair_ids) == 2
        and pair_ids[1] in sets[pair_ids[0]]
        and pair_ids[0] in sets[pair_ids[1]]
    )

    # total_enrolled <= 0 means unknown enrollment (v1 parity): skip the
    # oversubscription hold instead of holding every session.
    oversubscribed = (
        total_enrolled is not None
        and total_enrolled > 0
        and len(reports) > total_enrolled
    )
    anchored: set[str] = set()
    if n:
        # Layer 8 (user decision): the room with the largest post-federation
        # verified population IS the main class. No ratio floor, no face gate.
        # Tie-break: earliest-built component (smallest member id) in the room.
        core_room = max(rooms, key=lambda r: (len(rooms[r]), -room_min_comp[r]))
        anchored = rooms[core_room]

    surplus_held: set[str] = set()
    if oversubscribed:
        if pair_direct and len(pair_ids) > total_enrolled:
            surplus_held.update(pair_ids[total_enrolled:])
        elif len(anchored) > total_enrolled:
            ranked_anchored = sorted(
                anchored,
                key=lambda u: (0 if u in direct else 1, -m[u], t_of[u] - m[u], u),
            )
            surplus_held.update(ranked_anchored[total_enrolled:])

    # Precompute O(E) inverted index of verified peers claiming each submitter.
    verified_in_claims: dict[str, list[str]] = {}
    for v in sorted(lists):
        if v not in verified:
            continue
        for peer in lists[v]:
            if peer in lists:
                verified_in_claims.setdefault(peer, []).append(v)

    over_note = (
        f" (session oversubscribed: {len(reports)}/{total_enrolled})"
        if oversubscribed
        else ""
    )

    results: list[dict] = []
    for u in sorted(lists):
        mu = strong[u]
        if u in surplus_held:
            results.append(_row(
                u, UNVERIFIED, "SESSION_OVERSUBSCRIBED", 0.00, None,
                lists[u], mu,
                f"Surplus submission ({len(reports)} submitted > "
                f"{total_enrolled} enrolled) - Teacher check",
            ))
        elif pair_direct:
            partner = pair_ids[1] if u == pair_ids[0] else pair_ids[0]
            results.append(_row(
                u, VERIFIED, "DIRECT_CONNECTION", 1.00, None,
                lists[u], mut[u],
                f"Two-submitter session: mutual edge with [{partner}] - "
                f"verified (two-phone test)",
            ))
        elif u in anchored:
            if u in direct:
                results.append(_row(
                    u, VERIFIED, "DIRECT_CONNECTION", 1.00, None,
                    lists[u], mu,
                    f"Direct mutual with [{', '.join(mu)}] "
                    f"({m[u]}/{t_of[u]} threshold met)",
                ))
            else:
                b_node, cs = bridge_info[u]
                results.append(_row(
                    u, VERIFIED, "BRIDGE_CONNECTION", 1.00, b_node,
                    lists[u], mu,
                    f"Verified via bridge path through [{b_node}] to "
                    f"[{', '.join(cs)}]",
                ))
        elif u in verified:
            results.append(_row(
                u, UNVERIFIED, "MESH_UNANCHORED", 0.00, None, lists[u], mu,
                "Verified cluster could not be anchored to the main class "
                f"- Teacher check{over_note}",
            ))
        elif m[u] == 0:
            rescuers = verified_in_claims.get(u, [])
            if len(rescuers) >= 3:
                results.append(_row(
                    u, UNVERIFIED, "PEER_WITNESS", 0.00, None, lists[u], mu,
                    f"No corroborated mutuals but reported present by "
                    f"[{', '.join(rescuers)}] - likely radio failure - "
                    f"Teacher check{over_note}",
                ))
            else:
                results.append(_row(
                    u, UNVERIFIED, "NONE", 0.00, None, lists[u], mu,
                    "Submitted report with 0 corroborated mutuals - isolated "
                    f"desk or outside spoofer - Teacher check{over_note}",
                ))
        else:
            results.append(_row(
                u, UNVERIFIED, "BELOW_THRESHOLD", 0.00, None, lists[u], mu,
                f"Corroboration below threshold ({m[u]} mutuals, "
                f"needs {t_of[u]}) and bridge paths insufficient - "
                f"Teacher check{over_note}",
            ))

    # Offline witnessing; reporters must be verified.
    witness_map: dict[str, list[str]] = {}
    for u in lists:
        for peer in lists[u]:
            if peer not in lists:
                witness_map.setdefault(peer, []).append(u)
    for offline_id, witnesses in sorted(witness_map.items()):
        strong_witnesses = [w for w in witnesses if w in verified]
        if len(strong_witnesses) >= 3:
            results.append(_row(
                offline_id, UNVERIFIED, "PEER_WITNESS", 0.00,
                strong_witnesses[0], [], witnesses,
                f"Reported seen by {len(strong_witnesses)} verified peers "
                f"but never submitted - Teacher check",
            ))

    return results


def _components(adjacency: dict[str, list[str]]) -> list[set[str]]:
    seen: set[str] = set()
    out: list[set[str]] = []
    for start in sorted(adjacency):
        if start in seen:
            continue
        comp: set[str] = set()
        queue = deque([start])
        seen.add(start)
        while queue:
            cur = queue.popleft()
            comp.add(cur)
            for nbr in adjacency[cur]:
                if nbr not in seen:
                    seen.add(nbr)
                    queue.append(nbr)
        out.append(comp)
    return out


def _row(device_id, status, method, affinity, bridge, claimed, mutuals, remarks):
    return {
        "ble_device_id": device_id,
        "status": status,
        "verification_method": method,
        "affinity_score": affinity,
        "bridge_device_id": bridge,
        "reported_neighbors": list(claimed),
        "mutuals": list(mutuals),
        "remarks": remarks,
    }

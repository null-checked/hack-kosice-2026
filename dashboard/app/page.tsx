"use client";

/**
 * RubberDuck Live — main dashboard page.
 *
 * Above-the-fold (1440×900) grid:
 *   Row 1  ·  Hero (full-width)
 *   Row 2  ·  WellnessScore (3) | ActivityHeatmap (6) | MetricTile×2 stacked (3)
 *   Row 3  ·  FlowStateChart (8)      | AntiPatternsList (4)
 *   Row 4  ·  StuckMomentsTimeline (7) | SocraticFeed (5)
 *   Row 5  ·  EventStream (12, below fold)
 */

import { useCallback, useMemo, useState } from "react";
import { Activity, PlayCircle, Zap } from "lucide-react";

import { Hero } from "@/components/layout/Hero";
import { SessionSwitcher } from "@/components/layout/SessionSwitcher";
import { StatusPill } from "@/components/layout/StatusPill";
import { ActivityHeatmap } from "@/components/wellness/ActivityHeatmap";
import { AntiPatternsList } from "@/components/wellness/AntiPatternsList";
import { EventStream } from "@/components/wellness/EventStream";
import { FlowStateChart } from "@/components/wellness/FlowStateChart";
import { SocraticFeed } from "@/components/wellness/SocraticFeed";
import { StuckMomentsTimeline } from "@/components/wellness/StuckMomentsTimeline";
import { WellnessScore } from "@/components/wellness/WellnessScore";
import { MetricTile } from "@/components/viz/MetricTile";
import { useBackendHealth } from "@/hooks/useBackendHealth";
import { useDerivedMetrics } from "@/hooks/useDerivedMetrics";
import { useEventsSocket } from "@/hooks/useEventsSocket";
import { generateDemoReplay } from "@/lib/demoReplay";
import { shortenFile } from "@/lib/format";

const DEMO_REPLAY_ENABLED = process.env.NEXT_PUBLIC_DEMO_REPLAY === "1";

export default function DashboardPage() {
    const socket = useEventsSocket();
    const metrics = useDerivedMetrics(socket.events);
    const health = useBackendHealth();
    const [demoInjected, setDemoInjected] = useState(false);

    const injectDemo = useCallback(() => {
        socket.injectEvents(generateDemoReplay());
        setDemoInjected(true);
    }, [socket]);

    const hasEvents = socket.events.length > 0;

    const editsTrend = useMemo(
        () => metrics.flow.bins.map((b) => b.edit),
        [metrics.flow],
    );

    const topFile = metrics.topFiles[0];

    return (
        <main className="relative min-h-screen w-full mesh-page">
            <div className="mx-auto flex max-w-[1440px] flex-col gap-4 px-4 py-5 lg:gap-5 lg:px-6">
                {/* Row 1 — Hero */}
                <Hero
                    right={
                        <>
                            <StatusPill status={socket.status} reachable={health.reachable} />
                            <SessionSwitcher
                                sessions={socket.sessions}
                                value={socket.selectedSession}
                                onChange={socket.switchSession}
                            />
                            {DEMO_REPLAY_ENABLED && (
                                <button
                                    type="button"
                                    onClick={injectDemo}
                                    className="inline-flex items-center gap-1.5 rounded-full border border-violet-400/40 bg-violet-500/20 px-3 py-1.5 text-xs font-semibold text-violet-100 transition hover:border-violet-300 hover:bg-violet-500/30 focus:outline-none focus-visible:ring-2 focus-visible:ring-violet-300"
                                >
                                    <PlayCircle className="h-3.5 w-3.5" />
                                    {demoInjected ? "Replay demo" : "Demo replay"}
                                </button>
                            )}
                        </>
                    }
                />

                {/* Row 2 — score · heatmap · tiles */}
                <section className="grid grid-cols-1 gap-4 lg:grid-cols-12 lg:gap-5">
                    <WellnessScore
                        wellness={metrics.wellness}
                        empty={!hasEvents}
                        className="lg:col-span-3"
                    />
                    <ActivityHeatmap cells={metrics.heatmap} className="lg:col-span-6" />
                    <div className="flex flex-col gap-4 lg:col-span-3 lg:gap-5">
                        <MetricTile
                            label="Events / min"
                            value={metrics.eventRate.toFixed(1)}
                            hint="Rolling 5-min average"
                            trend={editsTrend}
                            trendColor="#22d3ee"
                            icon={<Activity className="h-4 w-4" />}
                        />
                        <MetricTile
                            label="Top file"
                            value={topFile ? shortenFile(topFile.file) : "—"}
                            hint={topFile ? `${topFile.edits} edits` : "No edits yet"}
                            icon={<Zap className="h-4 w-4" />}
                        />
                    </div>
                </section>

                {/* Row 3 — flow · anti-patterns */}
                <section className="grid grid-cols-1 gap-4 lg:grid-cols-12 lg:gap-5">
                    <FlowStateChart series={metrics.flow} className="lg:col-span-8" />
                    <AntiPatternsList items={metrics.antiPatterns} className="lg:col-span-4" />
                </section>

                {/* Row 4 — timeline · socratic feed */}
                <section className="grid grid-cols-1 gap-4 lg:grid-cols-12 lg:gap-5">
                    <StuckMomentsTimeline
                        moments={metrics.stuckMoments}
                        className="lg:col-span-7"
                    />
                    <SocraticFeed events={socket.events} className="lg:col-span-5" />
                </section>

                {/* Row 5 — event stream (below-fold) */}
                <section>
                    <EventStream events={socket.events} />
                </section>

                <footer className="flex flex-col items-center gap-1 pb-6 pt-2 text-[11px] text-muted-foreground sm:flex-row sm:justify-between">
                    <span>
                        Backend: {socket.url}
                        {health.health?.llm_model ? ` · LLM ${health.health.llm_model}` : ""}
                    </span>
                    <span>
                        {socket.events.length} events in memory
                        {socket.lastError ? ` · ${socket.lastError}` : ""}
                    </span>
                </footer>
            </div>
        </main>
    );
}

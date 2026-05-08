package vip.mate.workflow.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.workflow.compiler.ir.StepMode;
import vip.mate.workflow.compiler.ir.WorkflowGraph;
import vip.mate.workflow.compiler.ir.WorkflowStep;
import vip.mate.workflow.model.WorkflowRunEntity;
import vip.mate.workflow.model.WorkflowRunStepEntity;
import vip.mate.workflow.repository.WorkflowRunMapper;
import vip.mate.workflow.repository.WorkflowRunStepMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Linear executor for v0 workflows. Walks the graph step-by-step, batching
 * adjacent {@code fan_out} steps + terminating {@code collect} into a single
 * parallel group. The first failed (non-skipped) step aborts the run and
 * marks the row {@code failed}. The last non-skipped step's output payload
 * is recorded as {@code final_output_ref} on success.
 *
 * <p>StateGraph is intentionally not used here — the four base modes
 * (sequential / fan_out / collect / conditional) are linear plus one bounded
 * parallel section, which a small executor handles more directly. When
 * {@code await_approval} lands the runtime will switch to a graph-backed
 * scheduler so pause / resume can survive a JVM restart.
 */
@Slf4j
@Service
public class WorkflowRunner {

    private static final String STATE_RUNNING = "running";
    private static final String STATE_SUCCEEDED = "succeeded";
    private static final String STATE_FAILED = "failed";
    private static final String STATE_SKIPPED = "skipped";
    private static final String STATE_PAUSED = "paused";

    private static final ExecutorService FAN_OUT_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final WorkflowRunMapper runMapper;
    private final WorkflowRunStepMapper stepMapper;
    private final StepAdapterRegistry adapters;
    private final PayloadStore payloadStore;

    public WorkflowRunner(WorkflowRunMapper runMapper,
                          WorkflowRunStepMapper stepMapper,
                          StepAdapterRegistry adapters,
                          PayloadStore payloadStore) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.adapters = adapters;
        this.payloadStore = payloadStore;
    }

    public WorkflowRunResult run(WorkflowGraph graph, WorkflowRunRequest request) {
        WorkflowRunEntity runRow = openRun(request);
        String inputsRef = payloadStore.storeJson(request.workspaceId(), request.inputs());
        runRow.setInitialInputRef(inputsRef);
        runMapper.updateById(runRow);

        WorkflowRunContext ctx = new WorkflowRunContext(
                runRow.getId(),
                request.workspaceId(),
                request.workflowId(),
                request.revisionId(),
                request.inputs());

        return executeFromIndex(graph, ctx, runRow, /*fromIndex*/ 0, /*priorOutputRef*/ null);
    }

    /**
     * Continue an already-open run from {@code fromIndex}. Used by the resumer
     * after a pause settles. {@code priorOutputRef} is the last successful
     * step's output URI from before the pause — propagated so the
     * {@code final_output_ref} on success still points at meaningful data when
     * the post-resume tail of the run produces no further output.
     */
    public WorkflowRunResult continueFromIndex(WorkflowGraph graph, WorkflowRunContext ctx,
                                               WorkflowRunEntity runRow, int fromIndex,
                                               String priorOutputRef) {
        // Move the run row back to running so step-completion timestamps make
        // sense and the GC sweeper does not see a stale paused row.
        runRow.setState(STATE_RUNNING);
        runMapper.updateById(runRow);
        return executeFromIndex(graph, ctx, runRow, fromIndex, priorOutputRef);
    }

    private WorkflowRunResult executeFromIndex(WorkflowGraph graph, WorkflowRunContext ctx,
                                               WorkflowRunEntity runRow, int fromIndex,
                                               String priorOutputRef) {
        String lastSucceededOutputRef = priorOutputRef;
        try {
            int i = fromIndex;
            while (i < graph.steps().size()) {
                WorkflowStep step = graph.steps().get(i);
                int groupEnd = scanFanOutGroup(graph.steps(), i);
                if (groupEnd > i) {
                    GroupOutcome out = executeFanOutGroup(graph.steps(), i, groupEnd, ctx);
                    if (out.failed) {
                        return finishFailed(runRow, out.errorMessage);
                    }
                    if (out.lastOutputRef != null) lastSucceededOutputRef = out.lastOutputRef;
                    i = groupEnd + 1;
                } else {
                    StepResult result = executeStep(step, i, /*iterationIndex*/ null, ctx);
                    if (result.state() == StepResult.State.FAILED) {
                        return finishFailed(runRow, result.errorMessage());
                    }
                    if (result.state() == StepResult.State.PAUSED) {
                        return finishPaused(runRow, result.pauseToken());
                    }
                    if (result.outputPayloadUri() != null) {
                        lastSucceededOutputRef = result.outputPayloadUri();
                    }
                    i++;
                }
            }
            return finishSucceeded(runRow, lastSucceededOutputRef);
        } catch (RuntimeException e) {
            log.error("Workflow run {} aborted by unexpected exception", ctx.runId(), e);
            return finishFailed(runRow, "runtime error: " + e.getMessage());
        }
    }

    /**
     * Result of executing a contiguous {@code fan_out ... collect} block:
     * either every branch succeeded (or skipped) and the merged outputs are
     * already in the run context, or one branch failed and the runner aborts.
     */
    private record GroupOutcome(boolean failed, String errorMessage, String lastOutputRef) {}

    /**
     * If {@code steps[start]} is the head of a fan_out group (≥ 2 consecutive
     * fan_out followed by exactly one collect — the schema validator already
     * enforced this), return the index of the terminating collect. Otherwise
     * return {@code start} so the caller treats it as a single-step.
     */
    private static int scanFanOutGroup(List<WorkflowStep> steps, int start) {
        if (!(steps.get(start).mode() instanceof StepMode.FanOut)) return start;
        int j = start;
        while (j < steps.size() && steps.get(j).mode() instanceof StepMode.FanOut) j++;
        if (j < steps.size() && steps.get(j).mode() instanceof StepMode.Collect) {
            return j;
        }
        return start;
    }

    private GroupOutcome executeFanOutGroup(List<WorkflowStep> steps, int from, int collectIdx,
                                            WorkflowRunContext ctx) {
        // Steps from..collectIdx-1 are fan_out branches; collectIdx is the join.
        record Branch(int stepIndex, WorkflowStep step, Future<StepResult> future) {}
        List<Branch> branches = new ArrayList<>();
        for (int i = from; i < collectIdx; i++) {
            int idx = i;
            WorkflowStep step = steps.get(i);
            Future<StepResult> future = FAN_OUT_EXECUTOR.submit(
                    () -> executeStep(step, idx, idx - from, ctx));
            branches.add(new Branch(idx, step, future));
        }

        String lastOutputRef = null;
        for (Branch branch : branches) {
            try {
                StepResult result = branch.future.get(resolveTimeoutSecs(branch.step), TimeUnit.SECONDS);
                if (result.state() == StepResult.State.FAILED) {
                    return new GroupOutcome(true,
                            "fan_out branch '" + branch.step.name() + "' failed: " + result.errorMessage(),
                            null);
                }
                if (result.outputPayloadUri() != null) lastOutputRef = result.outputPayloadUri();
            } catch (Exception e) {
                return new GroupOutcome(true,
                        "fan_out branch '" + branch.step.name() + "' threw: " + e.getMessage(),
                        null);
            }
        }

        // Run the collect adapter so the join is captured as its own row.
        StepResult collectResult = executeStep(steps.get(collectIdx), collectIdx, null, ctx);
        if (collectResult.state() == StepResult.State.FAILED) {
            return new GroupOutcome(true, collectResult.errorMessage(), null);
        }
        return new GroupOutcome(false, null, lastOutputRef);
    }

    private static long resolveTimeoutSecs(WorkflowStep step) {
        if (step.timeoutSecs() == null || step.timeoutSecs() <= 0) return 600L;
        return step.timeoutSecs();
    }

    private StepResult executeStep(WorkflowStep step, int stepIndex, Integer iterationIndex,
                                   WorkflowRunContext ctx) {
        StepAdapter adapter = adapters.get(step.mode().typeName());
        WorkflowRunStepEntity stepRow = openStep(ctx.runId(), stepIndex, iterationIndex, step);

        long startNanos = System.nanoTime();
        StepResult result;
        try {
            result = adapter.execute(step, ctx);
        } catch (RuntimeException e) {
            log.error("Adapter {} threw on run={} stepIndex={} step='{}'",
                    step.mode().typeName(), ctx.runId(), stepIndex, step.name(), e);
            result = StepResult.failed("adapter threw: " + e.getMessage());
        }
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        // ctx.putOutput is synchronised internally so concurrent fan_out
        // branches can commit their results back to the shared run context
        // without external locking.
        if (result.state() == StepResult.State.SUCCEEDED && step.outputVar() != null
                && !step.outputVar().isBlank() && result.outputValue() != null) {
            ctx.putOutput(step.outputVar(), result.outputValue());
        }

        closeStep(stepRow, result, elapsedMs);
        return result;
    }

    private WorkflowRunEntity openRun(WorkflowRunRequest request) {
        WorkflowRunEntity row = new WorkflowRunEntity();
        row.setWorkflowId(request.workflowId());
        row.setRevisionId(request.revisionId());
        row.setWorkspaceId(request.workspaceId());
        row.setState(STATE_RUNNING);
        row.setTriggeredBy(request.triggeredBy());
        row.setStartedAt(LocalDateTime.now());
        runMapper.insert(row);
        return row;
    }

    private WorkflowRunResult finishSucceeded(WorkflowRunEntity runRow, String finalOutputRef) {
        runRow.setState(STATE_SUCCEEDED);
        runRow.setFinalOutputRef(finalOutputRef);
        runRow.setCompletedAt(LocalDateTime.now());
        runMapper.updateById(runRow);
        return new WorkflowRunResult(runRow.getId(), STATE_SUCCEEDED, finalOutputRef, null);
    }

    private WorkflowRunResult finishFailed(WorkflowRunEntity runRow, String errorMessage) {
        runRow.setState(STATE_FAILED);
        runRow.setErrorMessage(errorMessage);
        runRow.setCompletedAt(LocalDateTime.now());
        runMapper.updateById(runRow);
        return new WorkflowRunResult(runRow.getId(), STATE_FAILED, null, errorMessage);
    }

    private WorkflowRunResult finishPaused(WorkflowRunEntity runRow, String pauseToken) {
        runRow.setState(STATE_PAUSED);
        // Pause leaves the run open — completedAt stays null until resume settles it.
        runMapper.updateById(runRow);
        return new WorkflowRunResult(runRow.getId(), STATE_PAUSED, null, "pauseToken=" + pauseToken);
    }

    private WorkflowRunStepEntity openStep(long runId, int stepIndex, Integer iterationIndex,
                                           WorkflowStep step) {
        WorkflowRunStepEntity row = new WorkflowRunStepEntity();
        row.setRunId(runId);
        row.setStepIndex(stepIndex);
        row.setIterationIndex(iterationIndex);
        row.setStepName(step.name());
        row.setAgentId(step.agentId());
        row.setState(STATE_RUNNING);
        row.setOutputContentType(step.effectiveOutputContentType());
        row.setStartedAt(LocalDateTime.now());
        stepMapper.insert(row);
        return row;
    }

    private void closeStep(WorkflowRunStepEntity row, StepResult result, long durationMs) {
        switch (result.state()) {
            case SUCCEEDED -> row.setState(STATE_SUCCEEDED);
            case SKIPPED   -> row.setState(STATE_SKIPPED);
            case FAILED    -> row.setState(STATE_FAILED);
            case PAUSED    -> row.setState(STATE_PAUSED);
        }
        row.setOutputRef(result.outputPayloadUri());
        if (result.outputContentType() != null) {
            row.setOutputContentType(result.outputContentType());
        }
        row.setOutputSummary(result.outputSummary());
        row.setErrorMessage(result.errorMessage());
        row.setDurationMs(durationMs);
        row.setCompletedAt(LocalDateTime.now());
        stepMapper.updateById(row);
    }

}

package vip.mate.workflow.runtime.mode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import vip.mate.workflow.compiler.ir.StepMode;
import vip.mate.workflow.compiler.ir.WorkflowStep;
import vip.mate.workflow.model.WorkflowRunPauseEntity;
import vip.mate.workflow.model.WorkflowRunStepEntity;
import vip.mate.workflow.repository.WorkflowRunPauseMapper;
import vip.mate.workflow.repository.WorkflowRunStepMapper;
import vip.mate.workflow.runtime.StepAdapter;
import vip.mate.workflow.runtime.StepResult;
import vip.mate.workflow.runtime.WorkflowRunContext;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * {@code await_approval} — pauses the run pending an external approval
 * decision. Inserts a {@code mate_workflow_run_pause} row keyed by a fresh
 * {@code pauseToken}, then returns {@link StepResult.State#PAUSED} so the
 * runner can short-circuit and mark the run row {@code paused}. Resume is
 * orchestrated by {@code WorkflowResumer} once the external approval (or
 * timeout) lands.
 *
 * <p>v0 stores no extra approval metadata — the workflow's {@code mate_tool_approval}
 * link will be wired in once the approval-driven resume callback exists.
 * The pause row's {@code resume_deadline} is honoured when the step
 * declares a {@code timeoutSecs}; otherwise it stays {@code null} and the
 * resumer treats the pause as open-ended.
 */
@Component
public class AwaitApprovalStepAdapter implements StepAdapter {

    private final WorkflowRunPauseMapper pauseMapper;
    private final WorkflowRunStepMapper stepMapper;

    public AwaitApprovalStepAdapter(WorkflowRunPauseMapper pauseMapper,
                                    WorkflowRunStepMapper stepMapper) {
        this.pauseMapper = pauseMapper;
        this.stepMapper = stepMapper;
    }

    @Override
    public String typeName() { return "await_approval"; }

    @Override
    public StepResult execute(WorkflowStep step, WorkflowRunContext context) {
        if (!(step.mode() instanceof StepMode.AwaitApproval cfg)) {
            return StepResult.failed("await_approval adapter received non-await mode: "
                    + step.mode().typeName());
        }

        // Look up the freshly opened step row so we can link the pause to it.
        WorkflowRunStepEntity stepRow = stepMapper.selectOne(new LambdaQueryWrapper<WorkflowRunStepEntity>()
                .eq(WorkflowRunStepEntity::getRunId, context.runId())
                .eq(WorkflowRunStepEntity::getStepName, step.name())
                .orderByDesc(WorkflowRunStepEntity::getId)
                .last("LIMIT 1"));
        if (stepRow == null) {
            return StepResult.failed("await_approval could not locate its run-step row");
        }

        String pauseToken = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        WorkflowRunPauseEntity pause = new WorkflowRunPauseEntity();
        pause.setRunId(context.runId());
        pause.setStepId(stepRow.getId());
        pause.setPauseKind("await_approval");
        pause.setPauseToken(pauseToken);
        pause.setPausedAt(now);
        if (cfg.timeoutSecs() != null && cfg.timeoutSecs() > 0) {
            pause.setResumeDeadline(now.plusSeconds(cfg.timeoutSecs()));
        }
        pauseMapper.insert(pause);

        return StepResult.paused(pauseToken,
                "awaiting " + (cfg.approvalKind() == null ? "approval" : cfg.approvalKind()));
    }
}

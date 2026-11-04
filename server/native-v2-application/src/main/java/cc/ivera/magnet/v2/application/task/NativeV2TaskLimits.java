package cc.ivera.magnet.v2.application.task;

public record NativeV2TaskLimits(int maxSelectedFiles) {

    public NativeV2TaskLimits {
        if (maxSelectedFiles <= 0) {
            throw new IllegalArgumentException("maxSelectedFiles 必须为正数");
        }
    }
}

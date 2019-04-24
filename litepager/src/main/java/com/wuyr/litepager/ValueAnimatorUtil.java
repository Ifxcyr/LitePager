package com.wuyr.litepager;

import android.animation.ValueAnimator;
import android.support.annotation.NonNull;

import java.lang.reflect.Field;

/**
 * @author wuyr
 * @github https://github.com/wuyr/LitePager
 * @since 2019-04-03 上午10:37
 */
class ValueAnimatorUtil {

    /**
     * 重置动画缩放时长
     */
    static void resetDurationScale() {
        try {
            getField().setFloat(null, 1);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static float getDurationScale() {
        try {
            return getField().getFloat(null);
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    @NonNull
    private static Field getField() throws NoSuchFieldException {
        @SuppressWarnings("JavaReflectionMemberAccess")
        Field field = ValueAnimator.class.getDeclaredField("sDurationScale");
        field.setAccessible(true);
        return field;
    }
}

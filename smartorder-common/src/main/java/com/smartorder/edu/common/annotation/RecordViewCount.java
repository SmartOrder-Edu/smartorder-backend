package com.smartorder.edu.common.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RecordViewCount {
    // 在注解中加入参数，方便复用，比如区分是“教案”还是“资料库”
    String targetType() default "LESSON_PLAN";
}

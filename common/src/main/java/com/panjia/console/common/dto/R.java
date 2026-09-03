package com.panjia.console.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应结果
 * <p>
 * 前端约定格式：{ code, msg, data }
 * <ul>
 *   <li>code = 200：成功</li>
 *   <li>code != 200：失败，msg 为错误信息</li>
 * </ul>
 *
 * @param <T> 数据类型
 */
@Data
public class R<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 响应码：200 成功 */
    private int code;

    /** 响应消息 */
    private String msg;

    /** 响应数据 */
    private T data;

    /**
     * 成功响应（无数据）
     */
    public static <T> R<T> ok() {
        R<T> r = new R<>();
        r.setCode(200);
        r.setMsg("操作成功");
        return r;
    }

    /**
     * 成功响应（带数据）
     */
    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.setCode(200);
        r.setMsg("操作成功");
        r.setData(data);
        return r;
    }

    /**
     * 失败响应
     *
     * @param msg 错误信息
     */
    public static <T> R<T> fail(String msg) {
        R<T> r = new R<>();
        r.setCode(500);
        r.setMsg(msg);
        return r;
    }

    /**
     * 失败响应（带状态码）
     *
     * @param code 错误码
     * @param msg  错误信息
     */
    public static <T> R<T> fail(int code, String msg) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMsg(msg);
        return r;
    }
}

package teligen.jcga.hesuanquery.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.thread.ExecutorBuilder;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import teligen.jcga.hesuanquery.entity.ExcelQueryEntity;
import teligen.jcga.hesuanquery.entity.HesuanEntity;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Service
public class HesuanQueryService {

    // 1. 得到日志对象
    private Logger logger = LoggerFactory.getLogger(HesuanQueryService.class);

    @Value("${hesuanquery.sendReqUrl}")
    public String SEND_REQ_URL;
    @Value("${hesuanquery.getResultByIdUrl}")
    public String GET_RESULT_URL;
    @Value("${hesuanquery.appid}")
    public String APPID;
    @Value("${hesuanquery.roleid}")
    public String ROLEID;
    @Value("${hesuanquery.userId}")
    public String USER_ID;
    @Value("${hesuanquery.userIdCard}")
    public String USERID_CARD;
    @Value("${hesuanquery.userName}")
    public String USER_NAME;

    /**
     * @author xuyu
     * @des 查询核酸功能
     * @date 20221115
     */
    public List<HesuanEntity> hesuanQuery(ExcelQueryEntity excelQueryEntity) throws Exception {

        String xm = excelQueryEntity.getXm();
        String sfzh = excelQueryEntity.getSfzh();
        //如果身份证为空直接返回
        if (StrUtil.isBlank(sfzh)) {
            return new ArrayList<>();
        }

        String res = "";
        //构建请求body
        JSONObject jsonObject = new JSONObject();
        jsonObject.set("queryIdCard", sfzh);
        if (!StrUtil.isBlank(xm)) jsonObject.set("queryName", xm);
        jsonObject.set("userId", USER_ID);
        jsonObject.set("userIdCard", USERID_CARD);
        jsonObject.set("userName", USER_NAME);

        logger.info("========开始发送请求,xm=" + xm + "。身份证号码=" + sfzh + "。========");
        //构建第一次请求发送
        res = HttpRequest.post(SEND_REQ_URL)
                .header("appid", APPID)
                .header("roleid", ROLEID)
                .header(Header.CONTENT_TYPE, "application/json")
                .header(Header.USER_AGENT, "PostmanRuntime/7.16.3")
                .header("Postman-Token", UUID.fastUUID().toString())
                .header(Header.ACCEPT, "*/*")
                .header(Header.CACHE_CONTROL, "no-cache")
                .removeHeader(Header.ACCEPT_LANGUAGE)
                .header(Header.CONNECTION, "keep-alive")
                .body(jsonObject.toString())
                .timeout(20000)
                .execute().body();
        logger.info("========开始发送请求,xm=" + xm + "。身份证号码=" + sfzh + "。结束，返回" + res + "========");

        //判断是否响应成功
        JSONObject j2 = JSONUtil.parseObj(res);
        if (!j2.get("message").toString().equals("操作成功")) {
            throw new Exception();
        }

        //获取请求requestId;
        logger.info("========构建requestId========");
        String temp1 = JSONUtil.parseObj(j2.get("data")).get("msg").toString();
        String requestId = temp1.split("=")[temp1.split("=").length - 1];
        logger.info("========本次requestId=" + requestId + "========");

        //开始发送第二次请求，获取做核酸数据
        JSONObject j3 = new JSONObject();
        j3.set("requestId", requestId);
        j3.set("userId", USER_ID);
        j3.set("userIdCard", USERID_CARD);
        j3.set("userName", USER_NAME);

        String params = "?requestId=" + requestId + "&userId=" + USER_ID + "&userIdCard=" + USERID_CARD + "&userName=" + USER_NAME;
        String URL2 = GET_RESULT_URL + params;
        String res1 = null;

        //循环60*5s=300s，6分钟，如果6分钟后发送120次还没有结果，便不管了
        for (int i = 0; i < 60; i++) {
            logger.info("========延时5s========");
            Thread.sleep(5000);
            logger.info("========开始发送第二次请求,xm=" + xm + "。身份证号码=" + sfzh + "。请求地址url=" + URL2 + "========");
            HttpRequest httpRequest = HttpRequest.post(URL2)
                    .header("appid", APPID)
                    .header("roleid", ROLEID)
                    .header(Header.CONTENT_TYPE, "application/json")
                    .header(Header.USER_AGENT, "PostmanRuntime/7.16.3")
                    .header("Postman-Token", UUID.fastUUID().toString())
                    .header(Header.ACCEPT, "*/*")
                    .header(Header.CACHE_CONTROL, "no-cache")
                    .removeHeader(Header.ACCEPT_LANGUAGE)
                    .header(Header.CONNECTION, "keep-alive")
                    .body(j3.toString())
                    .timeout(20000);
            res1 = httpRequest.execute().body();
            logger.info("========发送第二次请求结束,xm=" + xm + "。身份证号码=" + sfzh + "。返回res:" + res1 + "========");
            if (!res1.contains("正在查询中")) {
                break;
            }
        }

        //搭建结果实体类
        JSONObject j4 = JSONUtil.parseObj(res1);
        List<HesuanEntity> returnList = null;
        JSONArray j5 = j4.get("data", JSONObject.class)
                .get("getDataJsonResponse", JSONObject.class)
                .get("return", JSONObject.class)
                .get("data", JSONObject.class)
                .get("result", JSONArray.class);
        returnList = j5.toList(HesuanEntity.class);
        //对数据进行倒叙排序，按照采样时间
        Collections.sort(returnList);

        return returnList;
    }

    /**
     * @param excelQueryEntities
     * @des 处理批量查询核酸
     */
    public List<HesuanEntity> getBatchHesuanQuery(List<ExcelQueryEntity> excelQueryEntities) throws InterruptedException {

        List<HesuanEntity> returnList = new CopyOnWriteArrayList<>();
        Set<ExcelQueryEntity> set = listToSetAndTrim(excelQueryEntities);

        //创建线程池
        ExecutorService executor = ExecutorBuilder.create()
                .setCorePoolSize(250)
                .setMaxPoolSize(500)
                .setWorkQueue(new LinkedBlockingQueue<>(10000))
                .build();

        set.forEach(excelQueryEntity -> {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    List<HesuanEntity> list = null;
                    try {
                        list = hesuanQuery(excelQueryEntity);
                    } catch (Exception e) {
                        logger.info("========批量查询有报错，报错实体：" + excelQueryEntity.toString() + "========");
                        e.printStackTrace();
                    }
                    list.forEach(hesuanEntity -> {
                        returnList.add(hesuanEntity);
                    });
                }
            });
        });

        //停止线程
        executor.shutdown();

        while (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        }

        return returnList;
    }

    /**
     * @param excelQueryEntities
     * @return
     * @des 对实体类进行去重和去空格，返回set
     */
    public Set<ExcelQueryEntity> listToSetAndTrim(List<ExcelQueryEntity> excelQueryEntities) {
        //entity去除前后空格
        excelQueryEntities.forEach(excelQueryEntity -> {
            excelQueryEntity.trim();
        });

        //去重
        Set<ExcelQueryEntity> set = new HashSet<>();
        excelQueryEntities.forEach(excelQueryEntity -> {
            set.add(excelQueryEntity);
        });

        return set;
    }

    public static void main(String[] args) throws FileNotFoundException {

        FileInputStream fileInputStream = new FileInputStream("c:\\xuyu\\1.txt");

        String test = FileUtil.readString("c:\\xuyu\\1.txt", CharsetUtil.CHARSET_UTF_8);
        String s1 = "{\"flag\":true,\"code\":20000,\"message\":\"操作成功\",\"data\":\"{\\\"getDataJsonResponse\\\":{\\\"return\\\":\\\"{\\\\\\\"code\\\\\\\":1,\\\\\\\"errorFlag\\\\\\\":1,\\\\\\\"hasSearch\\\\\\\":true,\\\\\\\"message\\\\\\\":\\\\\\\"成功\\\\\\\",\\\\\\\"realDataType\\\\\\\":2,\\\\\\\"data\\\\\\\":{\\\\\\\"totalRecords\\\\\\\":36,\\\\\\\"pageNo\\\\\\\":1,\\\\\\\"pageSize\\\\\\\":50,\\\\\\\"result\\\\\\\":[{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-10 18:09:34\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"三亚湾市场采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-11 04:00:25\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华银康医学检验有限公司\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-11 14:47:27\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场T3航站楼8号门(机场员工)核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-12 00:16:45\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-12 14:49:08\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场护宾楼核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-13 01:38:37\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-13 14:51:10\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场T3航站楼8号门(机场员工)核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-13 20:53:24\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-14 14:34:58\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场T3航站楼8号门(机场员工)核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-14 21:56:13\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-15 18:02:10\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"春森彼岸重庆便民核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-16 00:50:20\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆千麦医学检验实验室有限公司\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-16 16:59:45\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"三亚湾市场采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-16 23:59:45\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华银康医学检验有限公司\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-17 15:15:13\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"三亚湾市场采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-17 20:57:17\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华银康医学检验有限公司\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-18 11:10:48\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场T3航站楼8号门(机场员工)核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-18 17:37:15\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-19 14:52:15\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场护宾楼核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-19 22:58:13\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-20 15:01:45\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场T3航站楼8号门(机场员工)核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-20 20:55:56\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-21 09:36:58\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场动力能源西区制冷1站核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-21 17:22:56\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-22 11:11:30\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"锦绣华城采样点1\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-22 17:02:28\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆迪安医学检验中心\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-23 09:19:23\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区宝圣湖街道三亚湾核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-23 18:21:22\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆龙湖医院\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-24 10:01:31\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场动力能源西区制冷1站核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-24 17:01:58\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-25 10:09:16\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场T3航站楼8号门(机场员工)核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-25 17:52:25\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-26 10:53:42\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场T3航站楼8号门(机场员工)核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-26 17:40:05\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-27 14:40:54\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区宝圣湖街道英利狮城花园核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-27 21:37:47\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆迪安医学检验中心\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-28 10:48:10\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场T3航站楼8号门(机场员工)核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-28 19:44:30\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-29 23:44:01\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"重医一院金山医院常态化核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-30 02:10:54\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆医科大学附属第一医院金山医院\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-30 20:25:18\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"江北区康华众联心血管病医院重庆便民核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-31 03:47:58\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆千麦医学检验实验室有限公司\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-10-31 14:49:45\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场护宾楼核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-10-31 23:04:08\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-11-01 15:37:22\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场动力能源西区制冷1站核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-11-01 22:24:13\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-11-02 14:53:04\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场护宾楼核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-11-02 23:11:31\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-11-03 15:01:33\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场T3航站楼8号门(机场员工)核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-11-03 21:35:26\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-11-04 14:39:32\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场T3航站楼8号门(机场员工)核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-11-04 23:14:32\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-11-05 13:47:48\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"春森彼岸小区采样点1\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-11-06 00:44:40\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"威米（重庆）医学检验中心有限公司\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-11-06 09:19:15\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区宝圣湖街道三亚湾核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-11-06 16:00:57\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆龙湖医院\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-11-07 19:34:12\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"金渝社区加工区6路人行道采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-11-08 00:50:02\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆浦洛通医学检验实验室\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-11-08 19:17:04\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"金渝社区加工区6路人行道采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-11-09 00:31:50\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆两江新区第一人民医院（两江新区核酸检测基地-北站）\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-11-09 10:30:07\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场T3航站楼8号门(机场员工)核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-11-09 18:10:16\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-11-10 10:49:49\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场T3航站楼8号门(机场员工)核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-11-10 16:28:13\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-11-11 10:55:51\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场安检楼核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-11-11 18:58:52\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆华大医学检验所\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-11-12 09:45:27\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场护宾楼核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-11-12 20:11:23\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆龙湖医院\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-11-13 09:23:16\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场护宾楼核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-11-13 19:05:30\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆龙湖医院\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"},{\\\\\\\"receivingTime\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"tubeBar\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"collectionTime\\\\\\\":\\\\\\\"2022-11-14 09:35:54\\\\\\\",\\\\\\\"appointmentCollectionName\\\\\\\":\\\\\\\"渝北区江北机场护宾楼核酸采样点\\\\\\\",\\\\\\\"sampling\\\\\\\":\\\\\\\"咽拭子\\\\\\\",\\\\\\\"cardNo\\\\\\\":\\\\\\\"500382199808230838\\\\\\\",\\\\\\\"testingNumber\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"isGroupName\\\\\\\":\\\\\\\"\\\\\\\",\\\\\\\"testCompleteStatus\\\\\\\":\\\\\\\"阴性\\\\\\\",\\\\\\\"name\\\\\\\":\\\\\\\"陈楠\\\\\\\",\\\\\\\"testCompleteTime\\\\\\\":\\\\\\\"2022-11-14 18:07:06\\\\\\\",\\\\\\\"receiveTestPointName\\\\\\\":\\\\\\\"重庆龙湖医院\\\\\\\",\\\\\\\"batchNumber\\\\\\\":\\\\\\\"\\\\\\\"}],\\\\\\\"title\\\\\\\":[{\\\\\\\"dataCode\\\\\\\":\\\\\\\"batchNumber\\\\\\\",\\\\\\\"dataName\\\\\\\":\\\\\\\"批号\\\\\\\",\\\\\\\"dataType\\\\\\\":\\\\\\\"数值型N\\\\\\\",\\\\\\\"shareKind\\\\\\\":\\\\\\\"2\\\\\\\",\\\\\\\"standardType\\\\\\\":\\\\\\\"2\\\\\\\",\\\\\\\"isFixed\\\\\\\":false,\\\\\\\"onlyQuery\\\\\\\":false,\\\\\\\"valid\\\\\\\":true},{\\\\\\\"dataCode\\\\\\\":\\\\\\\"testCompleteStatus\\\\\\\",\\\\\\\"dataName\\\\\\\":\\\\\\\"检测结果\\\\\\\",\\\\\\\"dataType\\\\\\\":\\\\\\\"字符串型C\\\\\\\",\\\\\\\"shareKind\\\\\\\":\\\\\\\"2\\\\\\\",\\\\\\\"standardType\\\\\\\":\\\\\\\"1\\\\\\\",\\\\\\\"isFixed\\\\\\\":false,\\\\\\\"onlyQuery\\\\\\\":false,\\\\\\\"valid\\\\\\\":true},{\\\\\\\"dataCode\\\\\\\":\\\\\\\"testCompleteTime\\\\\\\",\\\\\\\"dataName\\\\\\\":\\\\\\\"实验室检测时间\\\\\\\",\\\\\\\"dataType\\\\\\\":\\\\\\\"日期时间型T\\\\\\\",\\\\\\\"shareKind\\\\\\\":\\\\\\\"2\\\\\\\",\\\\\\\"standardType\\\\\\\":\\\\\\\"5\\\\\\\",\\\\\\\"isFixed\\\\\\\":false,\\\\\\\"onlyQuery\\\\\\\":false,\\\\\\\"valid\\\\\\\":true},{\\\\\\\"dataCode\\\\\\\":\\\\\\\"receivingTime\\\\\\\",\\\\\\\"dataName\\\\\\\":\\\\\\\"实验室接收样本时间\\\\\\\",\\\\\\\"dataType\\\\\\\":\\\\\\\"日期时间型T\\\\\\\",\\\\\\\"shareKind\\\\\\\":\\\\\\\"2\\\\\\\",\\\\\\\"standardType\\\\\\\":\\\\\\\"5\\\\\\\",\\\\\\\"isFixed\\\\\\\":false,\\\\\\\"onlyQuery\\\\\\\":false,\\\\\\\"valid\\\\\\\":true},{\\\\\\\"dataCode\\\\\\\":\\\\\\\"collectionTime\\\\\\\",\\\\\\\"dataName\\\\\\\":\\\\\\\"采样时间\\\\\\\",\\\\\\\"dataType\\\\\\\":\\\\\\\"日期时间型T\\\\\\\",\\\\\\\"shareKind\\\\\\\":\\\\\\\"2\\\\\\\",\\\\\\\"standardType\\\\\\\":\\\\\\\"5\\\\\\\",\\\\\\\"isFixed\\\\\\\":false,\\\\\\\"onlyQuery\\\\\\\":false,\\\\\\\"valid\\\\\\\":true},{\\\\\\\"dataCode\\\\\\\":\\\\\\\"appointmentCollectionName\\\\\\\",\\\\\\\"dataName\\\\\\\":\\\\\\\"采样点名\\\\\\\",\\\\\\\"dataType\\\\\\\":\\\\\\\"字符串型C\\\\\\\",\\\\\\\"shareKind\\\\\\\":\\\\\\\"2\\\\\\\",\\\\\\\"standardType\\\\\\\":\\\\\\\"1\\\\\\\",\\\\\\\"isFixed\\\\\\\":false,\\\\\\\"onlyQuery\\\\\\\":false,\\\\\\\"valid\\\\\\\":true},{\\\\\\\"dataCode\\\\\\\":\\\\\\\"isGroupName\\\\\\\",\\\\\\\"dataName\\\\\\\":\\\\\\\"采样类别\\\\\\\",\\\\\\\"dataType\\\\\\\":\\\\\\\"字符串型C\\\\\\\",\\\\\\\"shareKind\\\\\\\":\\\\\\\"2\\\\\\\",\\\\\\\"standardType\\\\\\\":\\\\\\\"1\\\\\\\",\\\\\\\"isFixed\\\\\\\":false,\\\\\\\"onlyQuery\\\\\\\":false,\\\\\\\"valid\\\\\\\":true},{\\\\\\\"dataCode\\\\\\\":\\\\\\\"sampling\\\\\\\",\\\\\\\"dataName\\\\\\\":\\\\\\\"采样方式\\\\\\\",\\\\\\\"dataType\\\\\\\":\\\\\\\"字符串型C\\\\\\\",\\\\\\\"shareKind\\\\\\\":\\\\\\\"2\\\\\\\",\\\\\\\"standardType\\\\\\\":\\\\\\\"1\\\\\\\",\\\\\\\"isFixed\\\\\\\":false,\\\\\\\"onlyQuery\\\\\\\":false,\\\\\\\"valid\\\\\\\":true},{\\\\\\\"dataCode\\\\\\\":\\\\\\\"tubeBar\\\\\\\",\\\\\\\"dataName\\\\\\\":\\\\\\\"条码\\\\\\\",\\\\\\\"dataType\\\\\\\":\\\\\\\"数值型N\\\\\\\",\\\\\\\"shareKind\\\\\\\":\\\\\\\"2\\\\\\\",\\\\\\\"standardType\\\\\\\":\\\\\\\"2\\\\\\\",\\\\\\\"isFixed\\\\\\\":false,\\\\\\\"onlyQuery\\\\\\\":false,\\\\\\\"valid\\\\\\\":true},{\\\\\\\"dataCode\\\\\\\":\\\\\\\"cardNo\\\\\\\",\\\\\\\"dataName\\\\\\\":\\\\\\\"证件号\\\\\\\",\\\\\\\"dataType\\\\\\\":\\\\\\\"数值型N\\\\\\\",\\\\\\\"filterType\\\\\\\":\\\\\\\"eq\\\\\\\",\\\\\\\"shareKind\\\\\\\":\\\\\\\"2\\\\\\\",\\\\\\\"standardType\\\\\\\":\\\\\\\"2\\\\\\\",\\\\\\\"isFixed\\\\\\\":false,\\\\\\\"onlyQuery\\\\\\\":true,\\\\\\\"valid\\\\\\\":false},{\\\\\\\"dataCode\\\\\\\":\\\\\\\"name\\\\\\\",\\\\\\\"dataName\\\\\\\":\\\\\\\"姓名\\\\\\\",\\\\\\\"dataType\\\\\\\":\\\\\\\"字符串型C\\\\\\\",\\\\\\\"filterType\\\\\\\":\\\\\\\"eq\\\\\\\",\\\\\\\"shareKind\\\\\\\":\\\\\\\"2\\\\\\\",\\\\\\\"standardType\\\\\\\":\\\\\\\"1\\\\\\\",\\\\\\\"isFixed\\\\\\\":false,\\\\\\\"onlyQuery\\\\\\\":true,\\\\\\\"valid\\\\\\\":false},{\\\\\\\"dataCode\\\\\\\":\\\\\\\"receiveTestPointName\\\\\\\",\\\\\\\"dataName\\\\\\\":\\\\\\\"检测机构\\\\\\\",\\\\\\\"dataType\\\\\\\":\\\\\\\"字符串型C\\\\\\\",\\\\\\\"shareKind\\\\\\\":\\\\\\\"2\\\\\\\",\\\\\\\"standardType\\\\\\\":\\\\\\\"1\\\\\\\",\\\\\\\"isFixed\\\\\\\":false,\\\\\\\"onlyQuery\\\\\\\":false,\\\\\\\"valid\\\\\\\":true},{\\\\\\\"dataCode\\\\\\\":\\\\\\\"testingNumber\\\\\\\",\\\\\\\"dataName\\\\\\\":\\\\\\\"编号\\\\\\\",\\\\\\\"dataType\\\\\\\":\\\\\\\"数值型N\\\\\\\",\\\\\\\"shareKind\\\\\\\":\\\\\\\"2\\\\\\\",\\\\\\\"standardType\\\\\\\":\\\\\\\"2\\\\\\\",\\\\\\\"isFixed\\\\\\\":false,\\\\\\\"onlyQuery\\\\\\\":false,\\\\\\\"valid\\\\\\\":true}],\\\\\\\"isPageable\\\\\\\":true}}\\\"}}\"}";
        JSONObject jsonObject = JSONUtil.parseObj(test);

        JSONArray j1 = jsonObject.get("data", JSONObject.class)
                .get("getDataJsonResponse", JSONObject.class)
                .get("return", JSONObject.class)
                .get("data", JSONObject.class)
                .get("result", JSONArray.class);

        List<HesuanEntity> list = j1.toList(HesuanEntity.class);

        System.out.println("ok");

    }

}

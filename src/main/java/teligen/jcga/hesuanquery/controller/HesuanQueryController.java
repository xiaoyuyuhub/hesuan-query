package teligen.jcga.hesuanquery.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;
import teligen.jcga.hesuanquery.entity.ExcelQueryEntity;
import teligen.jcga.hesuanquery.entity.HesuanEntity;
import teligen.jcga.hesuanquery.service.HesuanQueryService;
import teligen.jcga.hesuanquery.utils.GetJarPathUtil;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;

@RestController
@RequestMapping("")
public class HesuanQueryController {

    // 1. 得到日志对象
    private Logger logger = LoggerFactory.getLogger(HesuanQueryController.class);

    @Autowired
    HesuanQueryService hesuanQueryService;

    /**
     * @param sfzh
     * @return
     * @author xuyu
     * @date 20221116
     * @des
     */
    @RequestMapping("/hesuanquery")
    public ModelAndView hesuanQuery(String xm, String sfzh) {
        ModelAndView modelAndView = new ModelAndView("hesuan-query");
        modelAndView.addObject("sfzh", sfzh);
        modelAndView.addObject("xm", sfzh);
        List<HesuanEntity> returnList;

        if (!StrUtil.isBlank(sfzh)) {
            try {
                logger.info("========接收到请求xm=" + xm + "。身份证号码=" + sfzh + "。开始处理========");

                ExcelQueryEntity excelQueryEntity = new ExcelQueryEntity();
                excelQueryEntity.setSfzh(sfzh);
                excelQueryEntity.setXm(xm);
                returnList = hesuanQueryService.hesuanQuery(excelQueryEntity);
                modelAndView.addObject("hesuanList", returnList);
                modelAndView.addObject("code", "200");
                modelAndView.addObject("msg", "success");
                logger.info("========请求xm=" + xm + "。身份证号码=" + sfzh + "。处理结束========");
            } catch (Exception e) {
                e.printStackTrace();
                modelAndView.addObject("code", "400");
                modelAndView.addObject("msg", "查询失败，失败原因可能为市局核酸系统接口故障，请稍后在尝试或者联系ADSI系统管理员");
                logger.error("========请求xm=" + xm + "。身份证号码=" + sfzh + "。处理失败，请查看日志========");
            }
        }

        return modelAndView;
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("uploadFile") MultipartFile file, HttpServletRequest request, HttpServletResponse response) throws InterruptedException, IOException {
        if (file.isEmpty()) {
            return "文件为空";
        }

        // 获取当前时间
        String currentDate = new SimpleDateFormat("yyyy/MM/dd").format(new Date());
        // 保存文件的目录
        String folderPath = GetJarPathUtil.JAR_ROOT_PATH + currentDate;
        System.out.println("保存的路径地址：" + folderPath);
        // 判断是否需要创建目录
        File folderFile = new File(folderPath);
        if (!folderFile.exists()) {
            folderFile.mkdirs();
        }

        // 文件名
        String fileName = file.getOriginalFilename();
        fileName = String.valueOf(UUID.randomUUID()).replace("-", "") + fileName.substring(fileName.lastIndexOf("."));
        File destFile;


        destFile = new File(folderFile, fileName);
        System.out.println(destFile.getAbsolutePath());
        // 核心
        file.transferTo(destFile);
        String url = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + "/excel" + currentDate + fileName;
        System.out.println(String.format("上传成功，路径：%s", url));

        //文件上传保存到服务器成功后开始调用请求处理文件里面内容
        //读取excel文件
        ExcelReader excelReader = ExcelUtil.getReader(destFile);
        List<ExcelQueryEntity> list = excelReader.readAll(ExcelQueryEntity.class);
        List<HesuanEntity> hesuanEntityList = hesuanQueryService.getBatchHesuanQuery(list);
        //排序
        Collections.sort(hesuanEntityList);

        // 通过工具类创建writer
        ExcelWriter writer = ExcelUtil.getWriter(GetJarPathUtil.JAR_ROOT_PATH + "shuchu-" + destFile.getName());
        // 一次性写出内容，使用默认样式，强制输出标题
        // 合并单元格后的标题行，使用默认标题样式
        writer.merge(7, "查询结果，若空则未查询到结果");
        //自定义标题别名
        writer.addHeaderAlias("collectionTime", "采集时间");
        writer.addHeaderAlias("appointmentCollectionName", "采样地点");
        writer.addHeaderAlias("sampling", "采样类别");
        writer.addHeaderAlias("cardNo", "身份证号码");
        writer.addHeaderAlias("testCompleteStatus", "采样结果");
        writer.addHeaderAlias("name", "姓名");
        writer.addHeaderAlias("examDate", "考试时间");
        writer.addHeaderAlias("testCompleteTime", "检测时间");
        writer.addHeaderAlias("receiveTestPointName", "检测公司");
        writer.write(hesuanEntityList, true);
        // 关闭writer，释放内存
        writer.close();
        return destFile.getName();
    }

    @GetMapping("/downfile")
    public void downfile(String filename, HttpServletResponse response) throws Exception {
        response.reset();
        response.setContentType("application/octet-stream;charset=utf-8");
        response.setHeader(
                "Content-disposition",
                "attachment; filename=result.xlsx");
        try (
                BufferedInputStream bis = new BufferedInputStream(new FileInputStream(GetJarPathUtil.JAR_ROOT_PATH + "shuchu-" + filename));
                // 输出流
                BufferedOutputStream bos = new BufferedOutputStream(response.getOutputStream());
        ) {
            byte[] buff = new byte[1024];
            int len = 0;
            while ((len = bis.read(buff)) > 0) {
                bos.write(buff, 0, len);
            }
        }
    }

    @RequestMapping("/hesuanupload")
    public ModelAndView hesuanBatchUploadHtml() {
        return new ModelAndView("hesuan-batch-upload");
    }
}

package teligen.jcga.hesuanquery.controller;

import cn.hutool.core.util.StrUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;
import teligen.jcga.hesuanquery.entity.HesuanEntity;
import teligen.jcga.hesuanquery.service.HesuanQueryService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("")
public class HesuanQueryController {

    private static final String TMP_PATH = "C:/projects/tmp";
    // 1. 得到日志对象
    private Logger logger = LoggerFactory.getLogger(HesuanQueryController.class);

    @Autowired
    HesuanQueryService hesuanQueryService;

    /**
     * @param sfzh
     * @return
     * @author xuyu
     *
     * @date 20221116
     * @des
     */
    @RequestMapping("/hesuanquery")
    public ModelAndView hesuanQuery(String sfzh) {
        ModelAndView modelAndView = new ModelAndView("hesuan-query");
        modelAndView.addObject("sfzh", sfzh);
        List<HesuanEntity> returnList;

        if (!StrUtil.isBlank(sfzh)) {
            try {
                logger.info("========接收到请求身份证号码=" + sfzh + "。开始处理========");
                returnList = hesuanQueryService.hesuanQuery(sfzh);
                modelAndView.addObject("hesuanList", returnList);
                modelAndView.addObject("code", "200");
                modelAndView.addObject("msg", "success");
                logger.info("========请求身份证号码=" + sfzh + "。处理结束========");
            } catch (Exception e) {
                e.printStackTrace();
                modelAndView.addObject("code", "400");
                modelAndView.addObject("msg", "查询失败，失败原因可能为市局核酸系统接口故障，请稍后在尝试或者联系ADSI系统管理员");
                logger.error("========请求身份证号码=" + sfzh + "。处理失败，请查看日志========");
            }
        }

        return modelAndView;
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("uploadFile") MultipartFile file, HttpServletRequest request, HttpServletResponse response) {
        if (file.isEmpty()) {
            return "文件为空";
        }

        // 获取当前时间
        String currentDate = new SimpleDateFormat("/yyyy/MM/dd").format(new Date());
        // 保存文件的目录
        String folderPath = TMP_PATH + currentDate;
        System.out.println("保存的路径地址：" + folderPath);
        // 判断是否需要创建目录
        File folderFile = new File(folderPath);
        if (!folderFile.exists()) {
            folderFile.mkdirs();
        }

        // 文件名
        String fileName = file.getOriginalFilename();
        fileName = String.valueOf(UUID.randomUUID()).replace("-", "") + fileName.substring(fileName.lastIndexOf("."));

        try {
            File destFile = new File(folderFile, fileName);
            System.out.println(destFile.getAbsolutePath());
            // 核心
            file.transferTo(destFile);
            String url = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + "/img" + currentDate + fileName;
            System.out.println(String.format("上传成功，图片路径：%s", url));
            return "上传成功";
        } catch (IOException e) {
            return "上传失败";
        }
    }

    @RequestMapping("/hesuanupload")
    public ModelAndView hesuanBatchUploadHtml() {
        return new ModelAndView("hesuan-batch-upload");
    }
}

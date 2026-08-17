package com.knowly.clean.cleaner;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowly.common.enums.ParseStatus;
import com.knowly.core.model.RawDocument;
import org.junit.jupiter.api.Test;

/**
 * {@link DefaultTextCleaner} 单元测试。覆盖去噪、水印去除、重复段落、繁简统一。
 */
class DefaultTextCleanerTest {

    private final DefaultTextCleaner cleaner = new DefaultTextCleaner();

    private RawDocument docWith(String content) {
        return new RawDocument("doc-1", "/test.txt", "test.txt", "txt",
                content, null, "hash", ParseStatus.SUCCESS);
    }

    @Test
    void should_remove_zero_width_chars() {
        String result = cleaner.clean("正常文本\u200B\u200D内容", docWith(""));

        assertThat(result).doesNotContain("\u200B", "\u200D");
        assertThat(result).contains("正常文本").contains("内容");
    }

    @Test
    void should_remove_page_number_lines() {
        String text = "正文内容\n第 12 页\n更多正文\nPage 5\n- 3 -";
        String result = cleaner.clean(text, docWith(text));

        assertThat(result).doesNotContain("第 12 页").doesNotContain("Page 5");
        assertThat(result).contains("正文内容").contains("更多正文");
    }

    @Test
    void should_remove_print_marks() {
        // Adobe 打印页脚（守一验收 P1：19.5% chunk 被污染，内容随页变化故频率统计打不中）
        String text = "肾脏衰弱，糖份无法排出。\n"
                + "列印日期/时间：02/04/2005 23:20:53\n"
                + "列印页数：110\n"
                + "正文继续讲糖尿病。\n"
                + "打印页数：45\n"
                + "打印日期/时间：03/05/2006 01:02:03";
        String result = cleaner.clean(text, docWith(text));

        assertThat(result).doesNotContain("列印").doesNotContain("打印页数").doesNotContain("打印日期");
        assertThat(result).contains("肾脏衰弱").contains("正文继续讲糖尿病");
    }

    @Test
    void should_remove_page_number_inside_sentence() {
        // 深检发现：PDF 分页页脚数字嵌在句子中间（"对你来\n32\n说"切断句子），13% chunk 中招
        String text = "对你来\n32\n说，这是形。\n副肾皮\n97\n质荷尔蒙将瘀斑逼回心脏。";
        String result = cleaner.clean(text, docWith(text));

        // 页码行连同换行一起删除，句子两半紧邻（至多残留一个换行）
        assertThat(result).doesNotContain("\n32").doesNotContain("\n97").doesNotContain("32\n").doesNotContain("97\n");
        assertThat(result.replaceAll("\\s", "")).contains("对你来说").contains("副肾皮质荷尔蒙");
    }

    @Test
    void should_remove_download_site_marks() {
        // 深检发现：来源下载站整行广告水印（知盈/豆丁等）
        String text = "正文讲方剂。\n→知盈医学资源网http://w.knowwing.com 万部医学书籍视频免费下载\n继续正文。";
        String result = cleaner.clean(text, docWith(text));

        assertThat(result).doesNotContain("知盈").doesNotContain("免费下载");
        assertThat(result).contains("正文讲方剂").contains("继续正文");
    }

    @Test
    void should_remove_separator_lines() {
        String text = "标题\n=========\n正文\n------";
        String result = cleaner.clean(text, docWith(text));

        assertThat(result).doesNotContain("=========").doesNotContain("------");
        assertThat(result).contains("标题").contains("正文");
    }

    @Test
    void should_reduce_repeated_punctuation() {
        // OCR 重复噪声：。。。。 → 。
        String text = "这是句子。。。。还有内容！！！";
        String result = cleaner.clean(text, docWith(text));

        assertThat(result).doesNotContain("。。。。").doesNotContain("！！！");
    }

    @Test
    void should_remove_repeated_header_watermark() {
        // 模拟跨页重复的页眉水印（出现 ≥3 次）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append("倪海厦教学资料\n");   // 水印
            sb.append("第").append(i).append("章正文内容\n");
        }
        String result = cleaner.clean(sb.toString(), docWith(sb.toString()));

        // 水印行重复 ≥3 次 → 应被去除
        long watermarkCount = result.lines().filter(l -> l.contains("倪海厦教学资料")).count();
        assertThat(watermarkCount).isZero();
    }

    @Test
    void should_remove_repeated_paragraphs() {
        // 版权声明跨页重复出现（整块连续重复 ≥3 次）。
        // 修复后：块级去重先于行级水印检测，整块逐字重复的版权声明只保留首次。
        // 注：算法指纹基于"连续3行"且块长度 ≥30 字符（短于 30 的块不参与判重，避免误伤）。
        String line1 = "本书版权所有未经许可不得以任何方式复制或传播";
        String line2 = "违者必究本书内容仅供学习参考使用";
        String line3 = "如需转载请联系出版社获取授权许可";
        String block = line1 + "\n" + line2 + "\n" + line3 + "\n";
        // 三个完整 block 连续排列（模拟 PDF 每页底部都印版权声明）
        String text = block + block + block + "正文甲\n正文乙";
        String result = cleaner.clean(text, docWith(text));

        // 重复块只保留首次
        long blockCount = result.lines().filter(l -> l.contains(line1)).count();
        assertThat(blockCount).isEqualTo(1);
    }

    @Test
    void should_normalize_traditional_to_simplified() {
        String result = cleaner.clean("天行健君子以自強不息", docWith(""));

        assertThat(result).contains("自强").doesNotContain("強");
    }

    @Test
    void should_normalize_fullwidth_to_halfwidth() {
        String result = cleaner.clean("第１２３页", docWith(""));

        assertThat(result).contains("123");
    }

    @Test
    void should_collapse_excess_whitespace() {
        String text = "正文     内容\t\t之间";
        String result = cleaner.clean(text, docWith(text));

        assertThat(result).doesNotContain("     ");
    }

    @Test
    void should_handle_empty_text() {
        assertThat(cleaner.clean("", docWith(""))).isEmpty();
    }

    @Test
    void should_handle_plain_text_without_noise() {
        String text = "这是干净的中文文本。没有水印，没有噪声。";
        String result = cleaner.clean(text, docWith(text));

        assertThat(result).contains("干净的中文文本");
    }

    @Test
    void should_not_remove_unique_short_lines() {
        // 短行但只出现 1-2 次，不应当水印删除
        String text = "序\n第一章正文内容\n短标题\n第二章正文";
        String result = cleaner.clean(text, docWith(text));

        assertThat(result).contains("序").contains("短标题");
    }
}

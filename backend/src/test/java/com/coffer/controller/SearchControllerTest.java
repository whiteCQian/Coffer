package com.coffer.controller;

import com.coffer.file.application.FileService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 搜索接口测试：验证 keyword/tag 参数透传与默认分页。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileService fileService;

    @Test
    @SuppressWarnings("unchecked")
    void searchPassesKeywordAndTag() throws Exception {
        when(fileService.searchFiles(nullable(String.class), nullable(String.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/api/files/search")
                        .param("keyword", "合同")
                        .param("tag", "财务")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<String> kw = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> tg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Pageable> pg = ArgumentCaptor.forClass(Pageable.class);
        verify(fileService).searchFiles(kw.capture(), tg.capture(), pg.capture());
        assertThat(kw.getValue()).isEqualTo("合同");
        assertThat(tg.getValue()).isEqualTo("财务");
        assertThat(pg.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pg.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchWithTagOnlyPassesNullKeyword() throws Exception {
        when(fileService.searchFiles(nullable(String.class), nullable(String.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/api/files/search")
                        .param("tag", "财务"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<String> kw = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> tg = ArgumentCaptor.forClass(String.class);
        verify(fileService).searchFiles(kw.capture(), tg.capture(), any(Pageable.class));
        assertThat(kw.getValue()).isNull();
        assertThat(tg.getValue()).isEqualTo("财务");
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchDefaultsToSize10() throws Exception {
        when(fileService.searchFiles(nullable(String.class), nullable(String.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/api/files/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<Pageable> pg = ArgumentCaptor.forClass(Pageable.class);
        verify(fileService).searchFiles(nullable(String.class), nullable(String.class), pg.capture());
        assertThat(pg.getValue()).isInstanceOf(PageRequest.class);
        assertThat(pg.getValue().getPageSize()).isEqualTo(10);
        assertThat(pg.getValue().getPageNumber()).isZero();
    }
}

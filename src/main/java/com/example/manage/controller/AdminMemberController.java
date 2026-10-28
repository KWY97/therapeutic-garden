package com.example.manage.controller;

import com.example.manage.domain.Member;
import com.example.manage.dto.MemberCreateForm;
import com.example.manage.dto.MemberEditForm;
import com.example.manage.dto.MemberProfileView;
import com.example.manage.service.MemberAnalysisModelService;
import com.example.manage.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminMemberController {
    private final MemberService memberService;
    private final MemberAnalysisModelService analysisModel;

    /* ================================
       참가자 목록
    ================================= */

    @GetMapping("/members")
    public String memberList(
            Model model
    ) {

        List<Member> members =
                memberService.findAllMembers();

        model.addAttribute(
                "members",
                members
        );

        return "admin/member-list";
    }

    @GetMapping("/members/new")
    public String newMemberForm(Model model) {
        model.addAttribute("memberCreateForm", new MemberCreateForm());
        return "admin/member-create";
    }

    @PostMapping("/members")
    public String createMember(
            @Valid @ModelAttribute("memberCreateForm") MemberCreateForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            form.setPassword(null);
            return "admin/member-create";
        }

        Member member = memberService.createMember(form);
        if (member == null) {
            bindingResult.rejectValue("loginId", "duplicate", "이미 사용 중인 로그인 아이디입니다.");
            form.setPassword(null);
            return "admin/member-create";
        }

        redirectAttributes.addFlashAttribute("successMessage", "참가자를 등록했습니다.");
        return "redirect:/admin/members/" + member.getMemberId();
    }


    /* ================================
       참가자 상세
    ================================= */

    @GetMapping("/members/{memberId}")
    public String memberDetail(
            @PathVariable Long memberId,
            Model model
    ) {

        MemberProfileView member = memberService.findProfile(memberId);

        if (member == null) {
            return "redirect:/admin/members";
        }


        model.addAttribute(
                "member",
                member
        );

        model.addAttribute(
                "formattedPhone",
                member.getFormattedPhone()
        );

        return "admin/member-detail";
    }

    @GetMapping("/members/{memberId}/data")
    public String memberData(
            @PathVariable Long memberId,
            Model model
    ) {
        Member member = memberService.findMember(memberId);

        if (member == null) {
            return "redirect:/admin/members";
        }

        model.addAttribute("member", member);
        analysisModel.populate(memberId, member.getName(), model);
        model.addAttribute("analysisApiBase", "/admin/members/" + memberId + "/data/api");
        return "admin/member-data";
    }


    /* ================================
       참가자 수정 화면
    ================================= */

    @GetMapping("/members/{memberId}/edit")
    public String editMemberForm(
            @PathVariable Long memberId,
            Model model
    ) {

        Member member =
                memberService.findMember(memberId);

        if (member == null) {
            return "redirect:/admin/members";
        }


        MemberEditForm form =
                new MemberEditForm();


        form.setParticipantNo(
                member.getParticipantNo()
        );

        form.setGroupNo(
                member.getGroupNo()
        );

        form.setLoginId(
                member.getLoginId()
        );

        form.setName(
                member.getName()
        );


        /*
         * 수정 화면에서도 전화번호를
         * 010-1234-5678 형태로 보여준다.
         */
        String phone =
                member.getPhone();

        if (phone != null
                && phone.length() == 11) {

            phone =
                    phone.substring(0, 3)
                            + "-"
                            + phone.substring(3, 7)
                            + "-"
                            + phone.substring(7);
        }

        form.setPhone(phone);


        model.addAttribute(
                "memberId",
                memberId
        );

        model.addAttribute(
                "memberEditForm",
                form
        );


        return "admin/member-edit";
    }


    /* ================================
       참가자 수정
    ================================= */

    @PostMapping("/members/{memberId}/edit")
    public String updateMember(
            @PathVariable Long memberId,
            @Valid MemberEditForm form,
            BindingResult bindingResult,
            Model model
    ) {

        /*
         * Validation 실패
         */
        if (bindingResult.hasErrors()) {

            model.addAttribute(
                    "memberId",
                    memberId
            );

            return "admin/member-edit";
        }


        String result =
                memberService.updateMember(
                        memberId,
                        form.getParticipantNo(),
                        form.getGroupNo(),
                        form.getLoginId(),
                        form.getName(),
                        form.getPhone()
                );


        /*
         * 참가자 번호 중복
         */
        if (result.equals(
                "PARTICIPANT_NO_DUPLICATE"
        )) {

            model.addAttribute(
                    "errorMessage",
                    "이미 사용 중인 참가자 번호입니다."
            );

            model.addAttribute(
                    "memberId",
                    memberId
            );

            return "admin/member-edit";
        }


        /*
         * 로그인 아이디 중복
         */
        if (result.equals(
                "LOGIN_ID_DUPLICATE"
        )) {

            model.addAttribute(
                    "errorMessage",
                    "이미 사용 중인 로그인 아이디입니다."
            );

            model.addAttribute(
                    "memberId",
                    memberId
            );

            return "admin/member-edit";
        }


        /*
         * 참가자를 찾을 수 없는 경우
         */
        if (result.equals(
                "MEMBER_NOT_FOUND"
        )) {

            return "redirect:/admin/members";
        }


        /*
         * 정상적으로 수정된 경우
         */
        return "redirect:/admin/members/"
                + memberId;
    }


    /* ================================
       참가자 삭제
    ================================= */

    @PostMapping("/members/{memberId}/delete")
    public String deleteMember(
            @PathVariable Long memberId,
            RedirectAttributes redirectAttributes
    ) {
        try {
            memberService.deleteMember(memberId);
            redirectAttributes.addFlashAttribute("successMessage", "참가자를 삭제했습니다.");
        } catch (MemberService.ReferencedMemberException exception) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "테스트 계정의 분석 참조 대상인 참가자는 삭제할 수 없습니다. 참조 관계를 먼저 확인하세요.");
        } catch (DataIntegrityViolationException | TransactionException exception) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "연결된 데이터가 있어 참가자를 삭제할 수 없습니다. 참조 관계를 확인하세요.");
        }

        return "redirect:/admin/members";
    }
}

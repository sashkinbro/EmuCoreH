// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Super3 c410f82 (izzy2lost), preserving Supermodel GPL3+ provenance.
class CompositedRender3D final : public IRender3D
{
public:
  CompositedRender3D(IRender3D* inner, CRender2D* render2d, GlesPresenter* presenter)
    : m_inner(inner), m_render2d(render2d), m_presenter(presenter)
  {
  }

  void RenderFrame(void) override
  {
    if (m_presenter && m_render2d && m_render2d->HasFrame())
    {
      const uint32_t* pixels = m_render2d->GetFrameBufferARGB();
      m_presenter->UpdateFrameARGB(pixels, (int)m_render2d->GetFrameWidth(), (int)m_render2d->GetFrameHeight());
      m_presenter->Render(false);
    }
    if (m_inner)
      m_inner->RenderFrame();
  }

  void BeginFrame(void) override
  {
    if (m_inner)
      m_inner->BeginFrame();
  }

  void EndFrame(void) override
  {
    if (m_inner)
      m_inner->EndFrame();
  }

  void UploadTextures(unsigned level, unsigned x, unsigned y, unsigned width, unsigned height) override
  {
    if (m_inner)
      m_inner->UploadTextures(level, x, y, width, height);
  }

  void AttachMemory(const uint32_t* cullingRAMLoPtr,
                    const uint32_t* cullingRAMHiPtr,
                    const uint32_t* polyRAMPtr,
                    const uint32_t* vromPtr,
                    const uint16_t* textureRAMPtr) override
  {
    if (m_inner)
      m_inner->AttachMemory(cullingRAMLoPtr, cullingRAMHiPtr, polyRAMPtr, vromPtr, textureRAMPtr);
  }

  void SetStepping(int stepping) override
  {
    if (m_inner)
      m_inner->SetStepping(stepping);
  }

  bool Init(unsigned xOffset, unsigned yOffset, unsigned xRes, unsigned yRes, unsigned totalXRes, unsigned totalYRes) override
  {
    if (!m_inner)
      return true;
    return m_inner->Init(xOffset, yOffset, xRes, yRes, totalXRes, totalYRes);
  }

  void SetSunClamp(bool enable) override
  {
    if (m_inner)
      m_inner->SetSunClamp(enable);
  }

  void SetSignedShade(bool enable) override
  {
    if (m_inner)
      m_inner->SetSignedShade(enable);
  }

  float GetLosValue(int layer) override
  {
    if (!m_inner)
      return 0.0f;
    return m_inner->GetLosValue(layer);
  }

private:
  IRender3D* m_inner = nullptr;
  CRender2D* m_render2d = nullptr;
  GlesPresenter* m_presenter = nullptr;
};



